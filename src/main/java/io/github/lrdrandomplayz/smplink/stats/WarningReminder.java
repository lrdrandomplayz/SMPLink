package io.github.lrdrandomplayz.smplink.stats;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import io.github.lrdrandomplayz.smplink.PluginSettings;
import io.github.lrdrandomplayz.smplink.db.LinkRepository;
import io.github.lrdrandomplayz.smplink.db.LinkRepository.Link;
import io.github.lrdrandomplayz.smplink.db.SessionRepository;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.Warning;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Reminds players with an open activity-check warning in game, since many won't read a Discord DM:
 * a chat message plus a few seconds of action-bar countdown when they join, and a chat reminder at an
 * interval while they stay online. Lookups run on the database thread; messages go out on the main thread.
 */
public final class WarningReminder implements Listener {

    private static final long ACTION_BAR_TICKS = 40L;
    private static final int ACTION_BAR_REPEATS = 5;

    private record Progress(long playedSeconds, long leftSeconds, long deadline, long requiredSeconds) {
    }

    private final JavaPlugin plugin;
    private final PluginSettings settings;
    private final LinkRepository links;
    private final TrackingRepository tracking;
    private final SessionRepository sessions;
    private final Logger logger;
    private final DateTimeFormatter deadlineFormat;

    public WarningReminder(JavaPlugin plugin, PluginSettings settings, LinkRepository links, TrackingRepository tracking,
                           SessionRepository sessions) {
        this.plugin = plugin;
        this.settings = settings;
        this.links = links;
        this.tracking = tracking;
        this.sessions = sessions;
        this.logger = plugin.getLogger();
        this.deadlineFormat = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH).withZone(settings.statusZone());
    }

    public void start() {
        if (settings.ingameReminderMinutes() > 0) {
            long ticks = settings.ingameReminderMinutes() * 60L * 20L;
            plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    remind(player.getUniqueId(), false);
                }
            }, ticks, ticks);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        remind(event.getPlayer().getUniqueId(), true);
    }

    private void remind(UUID uuid, boolean joined) {
        progress(uuid).thenAccept(found -> found.ifPresent(progress -> {
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    show(player, progress, joined);
                }
            });
        })).exceptionally(error -> {
            logger.log(Level.WARNING, "Could not check the activity warning of " + uuid, error);
            return null;
        });
    }

    /** Empty when the player has no open warning. */
    private CompletableFuture<Optional<Progress>> progress(UUID uuid) {
        long[] minSeconds = {Math.round(settings.activityMinHours() * 3600)};
        return links.findByUuid(uuid).thenCompose(link -> {
            if (link.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.<Warning>empty());
            }
            if (link.get().original()) {
                minSeconds[0] = Math.round(settings.activityOriginalMinHours() * 3600);
                if (minSeconds[0] <= 0) {
                    return CompletableFuture.completedFuture(Optional.<Warning>empty());
                }
            }
            return tracking.pendingWarning(link.map(Link::discordId).orElseThrow());
        }).thenCompose(warning -> {
            if (warning.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.<Progress>empty());
            }
            Warning open = warning.get();
            long now = System.currentTimeMillis();
            return sessions.playedSince(open.windowStart(), Math.min(now, open.deadline())).thenApply(played -> {
                long seconds = played.getOrDefault(uuid, 0L) / 1000;
                return Optional.of(new Progress(seconds, Math.max(0, minSeconds[0] - seconds), open.deadline(), minSeconds[0]));
            });
        });
    }

    private void show(Player player, Progress progress, boolean joined) {
        if (progress.leftSeconds() == 0) {
            if (joined) {
                player.sendMessage(Component.text("✔ You've reached this week's activity quota. You're safe!", NamedTextColor.GREEN));
            }
            return;
        }
        String left = duration(progress.leftSeconds());
        String timeLeft = duration(Math.max(0, (progress.deadline() - System.currentTimeMillis()) / 1000));
        String required = hours(progress.requiredSeconds());
        player.sendMessage(Component.text()
                .append(Component.text("⚠ Activity check: ", NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text("play ", NamedTextColor.YELLOW))
                .append(Component.text(left + " more", NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text(" before " + deadlineFormat.format(Instant.ofEpochMilli(progress.deadline())) + " "
                        + zoneLabel() + " (in " + timeLeft + ") to avoid a strike. "
                        + "Progress: " + duration(progress.playedSeconds()) + " of " + required + ".", NamedTextColor.YELLOW))
                .build());
        if (joined) {
            long requiredSeconds = progress.requiredSeconds();
            int filled = (int) Math.round(Math.min(1.0, (double) progress.playedSeconds() / requiredSeconds) * 20);
            Component bar = Component.text()
                    .append(Component.text("⚠ ", NamedTextColor.GOLD))
                    .append(Component.text("|".repeat(filled), NamedTextColor.GREEN))
                    .append(Component.text("|".repeat(20 - filled), NamedTextColor.DARK_GRAY))
                    .append(Component.text(" " + left + " more · " + timeLeft + " left", NamedTextColor.GOLD))
                    .build();
            for (int i = 0; i < ACTION_BAR_REPEATS; i++) {
                // The action bar fades after a few seconds, so it's resent a few times to stay up for ~10s.
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        player.sendActionBar(bar);
                    }
                }, i * ACTION_BAR_TICKS);
            }
        }
    }

    /** "GMT+8" rather than the normalised "GMT+08:00". */
    private String zoneLabel() {
        return settings.statusZone().getId().replaceAll(":00$", "").replaceAll("([+-])0([0-9])", "$1$2");
    }

    private static String duration(long seconds) {
        Duration d = Duration.ofSeconds(seconds);
        if (d.toDays() > 0) {
            return d.toDays() + "d " + d.toHoursPart() + "h";
        }
        if (d.toHours() > 0) {
            return d.toHours() + "h " + d.toMinutesPart() + "m";
        }
        return Math.max(1, d.toMinutes()) + "m";
    }

    private static String hours(long seconds) {
        double hours = seconds / 3600.0;
        return (hours == Math.rint(hours) ? String.valueOf((long) hours) : String.format(Locale.ROOT, "%.1f", hours)) + "h";
    }
}
