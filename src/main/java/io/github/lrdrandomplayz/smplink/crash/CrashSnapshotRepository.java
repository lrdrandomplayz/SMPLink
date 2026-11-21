package io.github.lrdrandomplayz.smplink.crash;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import io.github.lrdrandomplayz.smplink.db.Database;

/** The rolling per-player snapshots that crash recovery compares against. Owns its table. */
public final class CrashSnapshotRepository {

    /** A full snapshot. {@code inventory} holds slots 0-40 then the 4 armor pieces (41-44). */
    public record Snapshot(UUID uuid, String name, long takenAt, String world, double x, double y, double z,
                           int level, float exp, byte[] inventory, byte[] ender) {
    }

    /** Position and time only. */
    public record Snap(long takenAt, String world, double x, double y, double z) {
    }

    public record PlayerRef(UUID uuid, String name) {
    }

    private final Database database;

    public CrashSnapshotRepository(Database database) {
        this.database = database;
    }

    public CompletableFuture<Void> init() {
        return database.submit(c -> {
            try (var statement = c.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS crash_snapshots (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            uuid TEXT NOT NULL,
                            name TEXT,
                            taken_at INTEGER NOT NULL,
                            world TEXT,
                            x REAL, y REAL, z REAL,
                            level INTEGER,
                            exp REAL,
                            inventory BLOB,
                            ender BLOB
                        )""");
                statement.execute("CREATE INDEX IF NOT EXISTS crash_snapshots_uuid ON crash_snapshots (uuid, taken_at)");
                statement.execute("CREATE INDEX IF NOT EXISTS crash_snapshots_time ON crash_snapshots (taken_at)");
            }
            return null;
        });
    }

    public CompletableFuture<Void> insert(Snapshot snapshot, int keep) {
        return insertAll(List.of(snapshot), keep);
    }

    /** Inserts the snapshots, then trims each of their players to the newest {@code keep}. */
    public CompletableFuture<Void> insertAll(List<Snapshot> snapshots, int keep) {
        if (snapshots.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return database.submit(c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement insert = c.prepareStatement("""
                    INSERT INTO crash_snapshots (uuid, name, taken_at, world, x, y, z, level, exp, inventory, ender)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""");
                 PreparedStatement prune = c.prepareStatement("""
                         DELETE FROM crash_snapshots WHERE uuid = ? AND id NOT IN (
                             SELECT id FROM crash_snapshots WHERE uuid = ? ORDER BY taken_at DESC, id DESC LIMIT ?)""")) {
                for (Snapshot s : snapshots) {
                    insert.setString(1, s.uuid().toString());
                    insert.setString(2, s.name());
                    insert.setLong(3, s.takenAt());
                    insert.setString(4, s.world());
                    insert.setDouble(5, s.x());
                    insert.setDouble(6, s.y());
                    insert.setDouble(7, s.z());
                    insert.setInt(8, s.level());
                    insert.setDouble(9, s.exp());
                    insert.setBytes(10, s.inventory());
                    insert.setBytes(11, s.ender());
                    insert.executeUpdate();
                    prune.setString(1, s.uuid().toString());
                    prune.setString(2, s.uuid().toString());
                    prune.setInt(3, keep);
                    prune.executeUpdate();
                }
                c.commit();
            } catch (java.sql.SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
            return null;
        });
    }

    /** Positions of a player's snapshots with {@code from <= taken_at <= to}, oldest first. */
    public CompletableFuture<List<Snap>> positions(UUID uuid, long from, long to) {
        return database.submit(c -> {
            List<Snap> out = new ArrayList<>();
            try (PreparedStatement statement = c.prepareStatement("""
                    SELECT taken_at, world, x, y, z FROM crash_snapshots
                    WHERE uuid = ? AND taken_at >= ? AND taken_at <= ? ORDER BY taken_at, id""")) {
                statement.setString(1, uuid.toString());
                statement.setLong(2, from);
                statement.setLong(3, to);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Snap(rs.getLong(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5)));
                    }
                }
            }
            return out;
        });
    }

    /** The newest snapshot with {@code taken_at <= time}. */
    public CompletableFuture<Optional<Snapshot>> latestBefore(UUID uuid, long time) {
        return database.submit(c -> {
            try (PreparedStatement statement = c.prepareStatement("""
                    SELECT name, taken_at, world, x, y, z, level, exp, inventory, ender FROM crash_snapshots
                    WHERE uuid = ? AND taken_at <= ? ORDER BY taken_at DESC, id DESC LIMIT 1""")) {
                statement.setString(1, uuid.toString());
                statement.setLong(2, time);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<Snapshot>empty();
                    }
                    return Optional.of(new Snapshot(uuid, rs.getString(1), rs.getLong(2), rs.getString(3),
                            rs.getDouble(4), rs.getDouble(5), rs.getDouble(6), rs.getInt(7), (float) rs.getDouble(8),
                            rs.getBytes(9), rs.getBytes(10)));
                }
            }
        });
    }

    /** Players with a snapshot taken in the window, with their latest name. */
    public CompletableFuture<List<PlayerRef>> playersBetween(long from, long to) {
        return database.submit(c -> {
            List<PlayerRef> out = new ArrayList<>();
            try (PreparedStatement statement = c.prepareStatement("""
                    SELECT uuid, name, MAX(taken_at) FROM crash_snapshots
                    WHERE taken_at >= ? AND taken_at <= ? GROUP BY uuid""")) {
                statement.setLong(1, from);
                statement.setLong(2, to);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        out.add(new PlayerRef(UUID.fromString(rs.getString(1)), rs.getString(2)));
                    }
                }
            }
            return out;
        });
    }

    /** The newest snapshot time overall, or 0. */
    public CompletableFuture<Long> newestTakenAt() {
        return scalar("SELECT MAX(taken_at) FROM crash_snapshots");
    }

    /** The newest play-session end overall, or 0 (sessions are checkpointed while the server runs). */
    public CompletableFuture<Long> newestSessionEnd() {
        return scalar("SELECT MAX(ended_at) FROM sessions");
    }

    private CompletableFuture<Long> scalar(String sql) {
        return database.submit(c -> {
            try (var statement = c.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    public CompletableFuture<Integer> deleteOlderThan(long time) {
        return database.submit(c -> {
            try (PreparedStatement statement = c.prepareStatement("DELETE FROM crash_snapshots WHERE taken_at < ?")) {
                statement.setLong(1, time);
                return statement.executeUpdate();
            }
        });
    }

    public CompletableFuture<Integer> count(UUID uuid) {
        return database.submit(c -> {
            try (PreparedStatement statement = c.prepareStatement("SELECT COUNT(*) FROM crash_snapshots WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }
}
