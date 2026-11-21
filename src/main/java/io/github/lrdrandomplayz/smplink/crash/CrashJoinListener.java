package io.github.lrdrandomplayz.smplink.crash;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Hands joining players to {@link CrashRecovery}. Register it with the plugin manager. */
public final class CrashJoinListener implements Listener {

    private final CrashRecovery recovery;

    public CrashJoinListener(CrashRecovery recovery) {
        this.recovery = recovery;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        recovery.onJoin(event.getPlayer());
    }
}
