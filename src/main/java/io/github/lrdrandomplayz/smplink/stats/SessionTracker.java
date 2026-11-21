package io.github.lrdrandomplayz.smplink.stats;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import io.github.lrdrandomplayz.smplink.db.SessionRepository;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Records a session per login so playtime can be totalled over the last week or month. */
public final class SessionTracker implements Listener {

    private static final long CHECKPOINT_TICKS = 20L * 60;

    private final JavaPlugin plugin;
    private final SessionRepository sessions;
    private final Logger logger;
    // Session ids arrive from the database thread after the join, hence futures rather than plain ids.
    private final Map<UUID, CompletableFuture<Long>> open = new ConcurrentHashMap<>();

    public SessionTracker(JavaPlugin plugin, SessionRepository sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.logger = plugin.getLogger();
    }

    /** Main thread. Also covers players already online after a reload. */
    public void start() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            begin(player.getUniqueId());
        }
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkpoint, CHECKPOINT_TICKS, CHECKPOINT_TICKS);
    }

    /** Main thread, during shutdown: closes every open session at the current time. */
    public void stop() {
        checkpoint();
        open.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        begin(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        CompletableFuture<Long> id = open.remove(event.getPlayer().getUniqueId());
        if (id != null) {
            long now = System.currentTimeMillis();
            id.thenCompose(value -> sessions.extend(List.of(value), now)).exceptionally(this::logFailure);
        }
    }

    private void begin(UUID uuid) {
        CompletableFuture<Long> id = sessions.open(uuid, System.currentTimeMillis());
        id.exceptionally(this::logFailure);
        open.put(uuid, id);
    }

    private void checkpoint() {
        long now = System.currentTimeMillis();
        List<Long> ids = open.values().stream()
                .filter(future -> future.isDone() && !future.isCompletedExceptionally())
                .map(CompletableFuture::join)
                .toList();
        if (!ids.isEmpty()) {
            sessions.extend(ids, now).exceptionally(this::logFailure);
        }
    }

    private <T> T logFailure(Throwable error) {
        logger.log(Level.WARNING, "Could not record a play session", error);
        return null;
    }
}
