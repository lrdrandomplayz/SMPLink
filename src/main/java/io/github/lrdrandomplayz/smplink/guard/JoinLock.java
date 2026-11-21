package io.github.lrdrandomplayz.smplink.guard;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.lrdrandomplayz.smplink.db.LinkRepository;
import io.github.lrdrandomplayz.smplink.db.LinkRepository.Link;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository;
import io.github.lrdrandomplayz.smplink.http.SyncBridge;
import io.github.lrdrandomplayz.smplink.service.PlayerService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.Plugin;

/**
 * Keeps everyone but staff out until an opening time, then opens by itself. Staff are operators and anyone whose
 * linked Discord account is a admin or the Discord server's owner. Players who are turned away see when the
 * server opens and how long that is. Set with /joinlock (Discord or console); kept in the database (kv table) so it
 * survives restarts.
 */
public final class JoinLock implements Listener {

    private static final String UNTIL = "join-lock.until";
    private static final String NOTE = "join-lock.note";
    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("EEEE d MMMM 'at' h:mm a", Locale.ENGLISH);

    private final Plugin plugin;
    private final TrackingRepository tracking;
    private final LinkRepository links;
    private final ZoneId zone;
    private final Logger logger;
    /** Whether a linked Discord account is staff (admin or the server owner); set once the bot is up. */
    private volatile Predicate<String> discordStaff = id -> false;
    private volatile long until;
    private volatile String note = "";

    public JoinLock(Plugin plugin, TrackingRepository tracking, LinkRepository links, ZoneId zone, Logger logger) {
        this.plugin = plugin;
        this.tracking = tracking;
        this.links = links;
        this.zone = zone;
        this.logger = logger;
        try {
            String saved = SyncBridge.await(tracking.value(UNTIL), PlayerService.DB_TIMEOUT_MS);
            until = saved == null || saved.isBlank() ? 0 : Long.parseLong(saved);
            String savedNote = SyncBridge.await(tracking.value(NOTE), PlayerService.DB_TIMEOUT_MS);
            note = savedNote == null ? "" : savedNote;
        } catch (Exception e) {
            logger.warning("[joinlock] Could not read the join lock, so joining is open: " + e.getMessage());
            until = 0;
        }
    }

    public void setDiscordStaff(Predicate<String> discordStaff) {
        this.discordStaff = discordStaff;
    }

    /** Checks every 30 seconds whether the opening time has come. */
    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (until > 0 && System.currentTimeMillis() >= until) {
                logger.info("[joinlock] Opening time reached; everyone can join again");
                save(0, "");
            }
        }, 20L * 30, 20L * 30);
        if (locked()) {
            logger.info("[joinlock] Joining is locked until " + shown(until) + "; only staff can join");
        }
    }

    public boolean locked() {
        return until > System.currentTimeMillis();
    }

    /** When the server opens (epoch ms), or 0 when it isn't locked. */
    public long until() {
        return locked() ? until : 0;
    }

    public String note() {
        return note;
    }

    /** Locks joining until {@code untilMs} and sends out everyone online who isn't staff. */
    public void lock(long untilMs, String extraNote, String who) {
        save(untilMs, extraNote == null ? "" : extraNote.trim());
        logger.info("[joinlock] " + who + " locked joining until " + shown(untilMs));
        // Who's online is read on the main thread, the staff lookups (database) run off it, then the kicks go back.
        Bukkit.getScheduler().runTask(plugin, () -> {
            java.util.List<UUID> online = Bukkit.getOnlinePlayers().stream().filter(p -> !p.isOp()).map(Player::getUniqueId).toList();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                java.util.List<UUID> out = online.stream().filter(uuid -> !isLinkedStaff(uuid)).toList();
                Bukkit.getScheduler().runTask(plugin, () -> out.forEach(uuid -> {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null && locked()) {
                        player.kick(Component.text(message(System.currentTimeMillis()), NamedTextColor.YELLOW));
                    }
                }));
            });
        });
    }

    public void unlock(String who) {
        save(0, "");
        logger.info("[joinlock] " + who + " opened joining");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!locked() || event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID uuid = event.getUniqueId();
        if (Bukkit.getOfflinePlayer(uuid).isOp() || isLinkedStaff(uuid)) {
            return;
        }
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,
                Component.text(message(System.currentTimeMillis()), NamedTextColor.YELLOW));
    }

    /** "Our SMP opens on Saturday 7 November at 1:00 PM (GMT+8).\nThat's in 2 days, 4 hours." */
    public String message(long now) {
        String text = io.github.lrdrandomplayz.smplink.Branding.name() + " opens on " + shown(until) + ".\nThat's in " + countdown(until - now) + ".";
        return note.isBlank() ? text : text + "\n\n" + note;
    }

    public String shown(long epochMs) {
        return shown(epochMs, zone);
    }

    static String shown(long epochMs, ZoneId zone) {
        ZonedDateTime time = Instant.ofEpochMilli(epochMs).atZone(zone);
        return SHOWN.format(time) + " (" + offset(time) + ")";
    }

    private static String offset(ZonedDateTime time) {
        int seconds = time.getOffset().getTotalSeconds();
        int hours = seconds / 3600, minutes = Math.abs(seconds % 3600) / 60;
        return "GMT" + (seconds >= 0 ? "+" : "-") + Math.abs(hours) + (minutes == 0 ? "" : ":" + String.format("%02d", minutes));
    }

    /** "2 days, 4 hours", "3 hours, 5 minutes", "under a minute". */
    static String countdown(long ms) {
        Duration left = Duration.ofMillis(Math.max(0, ms));
        long days = left.toDays(), hours = left.toHoursPart(), minutes = left.toMinutesPart();
        if (days > 0) {
            return plural(days, "day") + (hours > 0 ? ", " + plural(hours, "hour") : "");
        }
        if (hours > 0) {
            return plural(hours, "hour") + (minutes > 0 ? ", " + plural(minutes, "minute") : "");
        }
        return minutes > 0 ? plural(minutes, "minute") : "under a minute";
    }

    private static String plural(long n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s");
    }

    /** "2026-11-07 13:00" in the server's time zone (discord.status-timezone). */
    public long parse(String text) {
        return parse(text, zone);
    }

    static long parse(String text, ZoneId zone) {
        try {
            return LocalDateTime.parse(text.trim(), INPUT).atZone(zone).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Write the opening time like 2026-11-07 13:00 (24-hour clock).");
        }
    }

    private boolean isLinkedStaff(UUID uuid) {
        try {
            String discordId = SyncBridge.await(links.findByUuid(uuid), PlayerService.DB_TIMEOUT_MS).map(Link::discordId).orElse(null);
            return discordId != null && discordStaff.test(discordId);
        } catch (Exception e) {
            logger.warning("[joinlock] Could not check whether " + uuid + " is staff: " + e.getMessage());
            return false;
        }
    }

    private void save(long untilMs, String newNote) {
        until = untilMs;
        note = newNote;
        try {
            SyncBridge.await(tracking.setValue(UNTIL, String.valueOf(untilMs)), PlayerService.DB_TIMEOUT_MS);
            SyncBridge.await(tracking.setValue(NOTE, newNote), PlayerService.DB_TIMEOUT_MS);
        } catch (Exception e) {
            logger.warning("[joinlock] Could not save the join lock: " + e.getMessage());
        }
    }
}
