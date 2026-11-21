package io.github.lrdrandomplayz.smplink.inventory;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import io.github.lrdrandomplayz.smplink.db.InventoryRepository;
import io.github.lrdrandomplayz.smplink.db.InventoryRepository.CachedInventory;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class InventorySnapshotter implements Listener {

    private final JavaPlugin plugin;
    private final InventoryRepository repository;
    private final Logger logger;

    public InventorySnapshotter(JavaPlugin plugin, InventoryRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        this.logger = plugin.getLogger();
    }

    public void start(int intervalSeconds) {
        long ticks = intervalSeconds * 20L;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::saveAll, ticks, ticks);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        save(event.getPlayer());
    }

    /** Main thread only. */
    public void saveAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            save(player);
        }
    }

    private void save(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        CachedInventory cached;
        try {
            // Serialised here on the main thread; the item stacks are live views of the player's inventory.
            InventorySnapshot snapshot = InventorySnapshot.of(player);
            cached = new CachedInventory(
                    InventoryCodec.encode(snapshot.inventory()),
                    InventoryCodec.encode(snapshot.armor()),
                    InventoryCodec.encode(snapshot.ender()),
                    System.currentTimeMillis());
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not serialise the inventory of " + name, e);
            return;
        }
        repository.save(uuid, cached).exceptionally(error -> {
            logger.log(Level.WARNING, "Could not cache the inventory of " + name, error);
            return null;
        });
    }
}
