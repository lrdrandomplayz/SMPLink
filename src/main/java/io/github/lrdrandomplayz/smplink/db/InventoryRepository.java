package io.github.lrdrandomplayz.smplink.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class InventoryRepository {

    public record CachedInventory(byte[] inventory, byte[] armor, byte[] ender, long updatedAt) {
    }

    private final Database database;

    public InventoryRepository(Database database) {
        this.database = database;
    }

    public CompletableFuture<Void> save(UUID uuid, CachedInventory cached) {
        return database.submit(c -> {
            try (PreparedStatement statement = c.prepareStatement("""
                    INSERT INTO inv_cache (uuid, inventory, armor, ender, updated_at) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET
                        inventory = excluded.inventory,
                        armor = excluded.armor,
                        ender = excluded.ender,
                        updated_at = excluded.updated_at""")) {
                statement.setString(1, uuid.toString());
                statement.setBytes(2, cached.inventory());
                statement.setBytes(3, cached.armor());
                statement.setBytes(4, cached.ender());
                statement.setLong(5, cached.updatedAt());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Optional<CachedInventory>> load(UUID uuid) {
        return database.submit(c -> {
            try (PreparedStatement statement = c.prepareStatement(
                    "SELECT inventory, armor, ender, updated_at FROM inv_cache WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new CachedInventory(
                            rs.getBytes("inventory"),
                            rs.getBytes("armor"),
                            rs.getBytes("ender"),
                            rs.getLong("updated_at")));
                }
            }
        });
    }
}
