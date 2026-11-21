package io.github.lrdrandomplayz.smplink.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Play sessions, used for playtime over a time window (vanilla only counts all-time playtime). */
public final class SessionRepository {

    private final Database database;

    public SessionRepository(Database database) {
        this.database = database;
    }

    /** Starts a session and returns its id. */
    public CompletableFuture<Long> open(UUID uuid, long now) {
        return database.submit(c -> {
            try (PreparedStatement statement = c.prepareStatement(
                    "INSERT INTO sessions (uuid, started_at, ended_at) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, uuid.toString());
                statement.setLong(2, now);
                statement.setLong(3, now);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    /** Moves the end of open sessions up to now, so a crash loses at most one checkpoint interval. */
    public CompletableFuture<Void> extend(Collection<Long> ids, long now) {
        return database.submit(c -> {
            try (PreparedStatement statement = c.prepareStatement("UPDATE sessions SET ended_at = ? WHERE id = ?")) {
                for (long id : ids) {
                    statement.setLong(1, now);
                    statement.setLong(2, id);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        });
    }

    /** Milliseconds played per player between {@code since} and {@code now}, clipping sessions that straddle either edge. */
    public CompletableFuture<Map<UUID, Long>> playedSince(long since, long now) {
        return database.submit(c -> {
            Map<UUID, Long> totals = new HashMap<>();
            try (PreparedStatement statement = c.prepareStatement("""
                    SELECT uuid, SUM(MAX(0, MIN(ended_at, ?) - MAX(started_at, ?))) AS played
                    FROM sessions WHERE ended_at > ? GROUP BY uuid""")) {
                statement.setLong(1, now);
                statement.setLong(2, since);
                statement.setLong(3, since);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        totals.put(UUID.fromString(rs.getString("uuid")), rs.getLong("played"));
                    }
                }
            }
            return totals;
        });
    }

    /** One login to logout, in epoch milliseconds. */
    public record Interval(long start, long end) {
        public boolean contains(long time) {
            return time >= start && time <= end;
        }
    }

    /** A player's sessions overlapping {@code from}..{@code to}, oldest first. */
    public CompletableFuture<List<Interval>> sessionsBetween(UUID uuid, long from, long to) {
        return database.submit(c -> {
            List<Interval> intervals = new java.util.ArrayList<>();
            try (PreparedStatement statement = c.prepareStatement(
                    "SELECT started_at, ended_at FROM sessions WHERE uuid = ? AND ended_at >= ? AND started_at <= ? ORDER BY started_at")) {
                statement.setString(1, uuid.toString());
                statement.setLong(2, from);
                statement.setLong(3, to);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        intervals.add(new Interval(rs.getLong("started_at"), rs.getLong("ended_at")));
                    }
                }
            }
            return intervals;
        });
    }

    /** When session tracking began; windows reaching further back than this are incomplete. */
    public CompletableFuture<Long> trackingStarted() {
        return database.submit(c -> {
            try (Statement statement = c.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT value FROM meta WHERE key = 'tracking_started'")) {
                return rs.next() ? Long.parseLong(rs.getString("value")) : System.currentTimeMillis();
            }
        });
    }
}
