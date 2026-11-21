package io.github.lrdrandomplayz.smplink.crash;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import io.github.lrdrandomplayz.smplink.inventory.InventoryCodec;
import io.github.lrdrandomplayz.smplink.inventory.InventorySnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Every {@code snapshot-seconds}, on the main thread, copies each online player's inventory, armor, ender chest,
 * position, level and exp into crash_snapshots (the database writes happen on the DB thread).
 */
public final class CrashSnapshotter {

    /** Inventory slots 0-40 then the four armor pieces, as one array (the table has no armor column). */
    public static final int ARMOR_OFFSET = InventorySnapshot.OFFHAND_SLOT + 1;

    private final CrashSnapshotRepository repository;
    private final CrashSettings settings;
    private final Logger logger;
    private BukkitTask task;

    public CrashSnapshotter(CrashSnapshotRepository repository, CrashSettings settings, Logger logger) {
        this.repository = repository;
        this.settings = settings;
        this.logger = logger;
    }

    public void start(Plugin plugin) {
        stop();
        long ticks = settings.snapshotSeconds() * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::snapshotAll, ticks, ticks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Main thread only. */
    public void snapshotAll() {
        List<CrashSnapshotRepository.Snapshot> batch = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                batch.add(capture(player, now));
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "[crash] couldn't snapshot " + player.getName(), e);
            }
        }
        repository.insertAll(batch, settings.keep()).exceptionally(e -> {
            logger.warning("[crash] couldn't save snapshots: " + e.getMessage());
            return null;
        });
    }

    static CrashSnapshotRepository.Snapshot capture(Player player, long now) {
        InventorySnapshot snapshot = InventorySnapshot.of(player);
        Location at = player.getLocation();
        return new CrashSnapshotRepository.Snapshot(player.getUniqueId(), player.getName(), now,
                at.getWorld() == null ? null : at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
                player.getLevel(), player.getExp(), InventoryCodec.encode(combined(snapshot)),
                InventoryCodec.encode(snapshot.ender()));
    }

    /** Inventory plus armor, the layout stored in the snapshot's inventory column. */
    public static ItemStack[] combined(InventorySnapshot snapshot) {
        ItemStack[] all = new ItemStack[ARMOR_OFFSET + 4];
        System.arraycopy(snapshot.inventory(), 0, all, 0, Math.min(snapshot.inventory().length, ARMOR_OFFSET));
        System.arraycopy(snapshot.armor(), 0, all, ARMOR_OFFSET, Math.min(snapshot.armor().length, 4));
        return all;
    }
}
