package io.github.lrdrandomplayz.smplink.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import io.github.lrdrandomplayz.smplink.stats.StatsReader.Counters;

/** Small bookkeeping tables: whitelist names the plugin added, reached milestones and weekly stat snapshots. */
public final class TrackingRepository {

    public record Milestone(UUID uuid, int hours, long reachedAt) {
    }

    public record Snapshot(long takenAt, Map<UUID, Counters> counters) {
    }

    private final Database database;

    public TrackingRepository(Database database) {
        this.database = database;
    }

    // --- whitelist ----------------------------------------------------------------------------------

    /** Lower-case name to name as written, for every name the plugin whitelisted. */
    public CompletableFuture<Map<String, String>> whitelisted() {
        return database.submit(c -> {
            Map<String, String> names = new LinkedHashMap<>();
            try (Statement statement = c.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT name_key, name FROM whitelist_sync")) {
                while (rs.next()) {
                    names.put(rs.getString("name_key"), rs.getString("name"));
                }
            }
            return names;
        });
    }

    public CompletableFuture<Void> recordWhitelisted(Map<String, String> added, Collection<String> removedKeys) {
        return database.submit(c -> {
            try (PreparedStatement add = c.prepareStatement("INSERT OR REPLACE INTO whitelist_sync (name_key, name) VALUES (?, ?)");
                 PreparedStatement remove = c.prepareStatement("DELETE FROM whitelist_sync WHERE name_key = ?")) {
                for (Map.Entry<String, String> entry : added.entrySet()) {
                    add.setString(1, entry.getKey());
                    add.setString(2, entry.getValue());
                    add.addBatch();
                }
                for (String key : removedKeys) {
                    remove.setString(1, key);
                    remove.addBatch();
                }
                add.executeBatch();
                remove.executeBatch();
            }
            return null;
        });
    }

    // --- milestones ---------------------------------------------------------------------------------

    public CompletableFuture<Set<UUID>> milestoneSeen() {
        return database.submit(c -> {
            Set<UUID> seen = new HashSet<>();
            try (Statement statement = c.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT uuid FROM milestone_seen")) {
                while (rs.next()) {
                    seen.add(UUID.fromString(rs.getString("uuid")));
                }
            }
            return seen;
        });
    }

    /** Milestone hours already recorded per player. */
    public CompletableFuture<Map<UUID, Set<Integer>>> milestonesReached() {
        return database.submit(c -> {
            Map<UUID, Set<Integer>> reached = new HashMap<>();
            try (Statement statement = c.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT uuid, hours FROM milestones")) {
                while (rs.next()) {
                    reached.computeIfAbsent(UUID.fromString(rs.getString("uuid")), id -> new HashSet<>())
                            .add(rs.getInt("hours"));
                }
            }
            return reached;
        });
    }

    /** Records milestones; {@code announced} is false for ones that were passed before tracking began. */
    public CompletableFuture<Void> recordMilestones(Collection<Milestone> milestones, boolean announced,
                                                    Collection<UUID> markSeen) {
        return database.submit(c -> {
            try (PreparedStatement add = c.prepareStatement(
                    "INSERT OR IGNORE INTO milestones (uuid, hours, reached_at, announced) VALUES (?, ?, ?, ?)");
                 PreparedStatement seen = c.prepareStatement("INSERT OR IGNORE INTO milestone_seen (uuid) VALUES (?)")) {
                for (Milestone milestone : milestones) {
                    add.setString(1, milestone.uuid().toString());
                    add.setInt(2, milestone.hours());
                    add.setLong(3, milestone.reachedAt());
                    add.setInt(4, announced ? 1 : 0);
                    add.addBatch();
                }
                for (UUID uuid : markSeen) {
                    seen.setString(1, uuid.toString());
                    seen.addBatch();
                }
                add.executeBatch();
                seen.executeBatch();
            }
            return null;
        });
    }

    /** Announced milestones reached at or after {@code since}. */
    public CompletableFuture<List<Milestone>> milestonesSince(long since) {
        return database.submit(c -> {
            List<Milestone> found = new java.util.ArrayList<>();
            try (PreparedStatement statement = c.prepareStatement(
                    "SELECT uuid, hours, reached_at FROM milestones WHERE announced = 1 AND reached_at >= ? ORDER BY reached_at")) {
                statement.setLong(1, since);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        found.add(new Milestone(UUID.fromString(rs.getString("uuid")), rs.getInt("hours"),
                                rs.getLong("reached_at")));
                    }
                }
            }
            return found;
        });
    }

    // --- activity-check warnings --------------------------------------------------------------------

    public record Warning(long id, String discordId, UUID uuid, String ign, long windowStart, long warnedAt,
                          long deadline) {
    }

    public CompletableFuture<Void> addWarning(String discordId, UUID uuid, String ign, long windowStart, long warnedAt,
                                              long deadline) {
        return database.submit(c -> {
            try (PreparedStatement statement = c.prepareStatement("""
                    INSERT INTO activity_warnings (discord_id, uuid, ign, window_start, warned_at, deadline, status)
                    VALUES (?, ?, ?, ?, ?, ?, 'pending')""")) {
                statement.setString(1, discordId);
                statement.setString(2, uuid.toString());
                statement.setString(3, ign);
                statement.setLong(4, windowStart);
                statement.setLong(5, warnedAt);
                statement.setLong(6, deadline);
                statement.executeUpdate();
            }
            return null;
        });
    }

    /** A stored value, or null. */
    public CompletableFuture<String> value(String key) {
        return database.submit(c -> {
            try (PreparedStatement select = c.prepareStatement("SELECT value FROM kv WHERE key = ?")) {
                select.setString(1, key);
                try (ResultSet rs = select.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    public CompletableFuture<Void> setValue(String key, String value) {
        return database.submit(c -> {
            try (PreparedStatement upsert = c.prepareStatement(
                    "INSERT INTO kv (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value")) {
                upsert.setString(1, key);
                upsert.setString(2, value);
                upsert.executeUpdate();
            }
            return null;
        });
    }

    /** Cancels every open activity warning (no strike, no DM); returns how many. */
    public CompletableFuture<Integer> cancelPendingWarnings(long at) {
        return database.submit(c -> {
            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE activity_warnings SET status = 'cancelled', resolved_at = ? WHERE status = 'pending'")) {
                update.setLong(1, at);
                return update.executeUpdate();
            }
        });
    }

    /** Takes back every strike of {@code kind} that hasn't been taken back yet; returns how many. */
    public CompletableFuture<Integer> removeStrikesOfKind(String kind, String removedBy, long at) {
        return database.submit(c -> {
            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE strikes SET removed_at = ?, removed_by = ? WHERE kind = ? AND removed_at IS NULL")) {
                update.setLong(1, at);
                update.setString(2, removedBy);
                update.setString(3, kind);
                return update.executeUpdate();
            }
        });
    }

    public CompletableFuture<List<Warning>> pendingWarnings() {
        return database.submit(c -> {
            List<Warning> warnings = new java.util.ArrayList<>();
            try (Statement statement = c.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT * FROM activity_warnings WHERE status = 'pending' ORDER BY deadline")) {
                while (rs.next()) {
                    warnings.add(new Warning(rs.getLong("id"), rs.getString("discord_id"),
                            UUID.fromString(rs.getString("uuid")), rs.getString("ign"), rs.getLong("window_start"),
                            rs.getLong("warned_at"), rs.getLong("deadline")));
                }
            }
            return warnings;
        });
    }

    /** A finished or open warning, for /lookup. {@code resolvedAt} is 0 while pending. */
    public record WarningRecord(String status, long warnedAt, long deadline, long resolvedAt) {
    }

    public CompletableFuture<java.util.Optional<Warning>> pendingWarning(String discordId) {
        return pendingWarnings().thenApply(all -> all.stream()
                .filter(warning -> warning.discordId().equals(discordId))
                .findFirst());
    }

    public record Outcome(String discordId, String ign, String status, long deadline, long resolvedAt) {
    }

    /** Warnings resolved since {@code since} (passed, removed, cancelled or failed), newest first. */
    public CompletableFuture<List<Outcome>> resolvedSince(long since) {
        return database.submit(c -> {
            List<Outcome> outcomes = new java.util.ArrayList<>();
            try (PreparedStatement statement = c.prepareStatement("""
                    SELECT discord_id, ign, status, deadline, resolved_at FROM activity_warnings
                    WHERE status <> 'pending' AND resolved_at >= ? ORDER BY resolved_at DESC""")) {
                statement.setLong(1, since);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        outcomes.add(new Outcome(rs.getString("discord_id"), rs.getString("ign"), rs.getString("status"),
                                rs.getLong("deadline"), rs.getLong("resolved_at")));
                    }
                }
            }
            return outcomes;
        });
    }

    /** Every warning ever given to this Discord account, newest first. */
    public CompletableFuture<List<WarningRecord>> warningHistory(String discordId) {
        return database.submit(c -> {
            List<WarningRecord> records = new java.util.ArrayList<>();
            try (PreparedStatement statement = c.prepareStatement(
                    "SELECT status, warned_at, deadline, resolved_at FROM activity_warnings WHERE discord_id = ? ORDER BY warned_at DESC")) {
                statement.setString(1, discordId);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        records.add(new WarningRecord(rs.getString("status"), rs.getLong("warned_at"),
                                rs.getLong("deadline"), rs.getLong("resolved_at")));
                    }
                }
            }
            return records;
        });
    }

    /**
     * status: passed, struck (missed the deadline, got a strike), removed (the strike that reached the
     * limit), cancelled (no longer linked) or failed (removal couldn't be done).
     */
    public CompletableFuture<Void> resolveWarning(long id, String status) {
        return database.submit(c -> {
            try (PreparedStatement statement = c.prepareStatement(
                    "UPDATE activity_warnings SET status = ?, resolved_at = ? WHERE id = ?")) {
                statement.setString(1, status);
                statement.setLong(2, System.currentTimeMillis());
                statement.setLong(3, id);
                statement.executeUpdate();
            }
            return null;
        });
    }

    // --- strikes and permanent removals ---------------------------------------------------------------

    /** kind is activity, raid or manual; removedAt is null unless an admin took it back with /strike remove. */
    public record Strike(long id, String discordId, String ign, String kind, String reason, String givenBy, long givenAt,
                         Long removedAt) {
    }

    public record RemovedMember(String discordId, String ign, long removedAt) {
    }

    private static final String STRIKE_COLUMNS = "id, discord_id, ign, kind, reason, given_by, given_at, removed_at";

    /**
     * Records a strike and returns its ID. For a missed activity-check deadline {@code warningId} is the
     * warning, and a second call for the same warning returns the existing strike instead of adding one.
     */
    public CompletableFuture<Long> addStrike(String discordId, UUID uuid, String ign, String kind, String reason,
                                             String givenBy, Long warningId, long givenAt) {
        return database.submit(c -> {
            if (warningId != null) {
                try (PreparedStatement existing = c.prepareStatement("SELECT id FROM strikes WHERE warning_id = ?")) {
                    existing.setLong(1, warningId);
                    try (ResultSet rs = existing.executeQuery()) {
                        if (rs.next()) {
                            return rs.getLong(1);
                        }
                    }
                }
            }
            try (PreparedStatement statement = c.prepareStatement("""
                    INSERT INTO strikes (discord_id, uuid, ign, kind, reason, given_by, warning_id, given_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, discordId);
                statement.setString(2, uuid.toString());
                statement.setString(3, ign);
                statement.setString(4, kind);
                statement.setString(5, reason);
                statement.setString(6, givenBy);
                statement.setObject(7, warningId);
                statement.setLong(8, givenAt);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    /** Strikes given after {@code since} and not removed, oldest first. */
    public CompletableFuture<List<Strike>> strikesSince(String discordId, long since) {
        return database.submit(c -> {
            List<Strike> strikes = new java.util.ArrayList<>();
            try (PreparedStatement statement = c.prepareStatement("SELECT " + STRIKE_COLUMNS + " FROM strikes"
                    + " WHERE discord_id = ? AND given_at > ? AND removed_at IS NULL ORDER BY given_at, id")) {
                statement.setString(1, discordId);
                statement.setLong(2, since);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        strikes.add(strike(rs));
                    }
                }
            }
            return strikes;
        });
    }

    public CompletableFuture<java.util.Optional<Strike>> findStrike(long id) {
        return database.submit(c -> {
            try (PreparedStatement statement = c.prepareStatement("SELECT " + STRIKE_COLUMNS + " FROM strikes WHERE id = ?")) {
                statement.setLong(1, id);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? java.util.Optional.of(strike(rs)) : java.util.Optional.empty();
                }
            }
        });
    }

    /** Takes a strike back; false if it doesn't exist or was already removed. */
    public CompletableFuture<Boolean> removeStrike(long id, String removedBy, long removedAt) {
        return database.submit(c -> {
            try (PreparedStatement statement = c.prepareStatement(
                    "UPDATE strikes SET removed_at = ?, removed_by = ? WHERE id = ? AND removed_at IS NULL")) {
                statement.setLong(1, removedAt);
                statement.setString(2, removedBy);
                statement.setLong(3, id);
                return statement.executeUpdate() > 0;
            }
        });
    }

    private static Strike strike(ResultSet rs) throws java.sql.SQLException {
        long removedAt = rs.getLong("removed_at");
        // wasNull() describes the last column read, so it has to be asked straight away.
        Long removed = rs.wasNull() ? null : removedAt;
        return new Strike(rs.getLong("id"), rs.getString("discord_id"), rs.getString("ign"), rs.getString("kind"),
                rs.getString("reason"), rs.getString("given_by"), rs.getLong("given_at"), removed);
    }

    public CompletableFuture<Void> recordRemoval(String discordId, UUID uuid, String ign, long removedAt) {
        return database.submit(c -> {
            try (PreparedStatement statement = c.prepareStatement(
                    "INSERT INTO removed_members (discord_id, uuid, ign, ign_key, removed_at) VALUES (?, ?, ?, ?, ?)")) {
                statement.setString(1, discordId);
                statement.setString(2, uuid.toString());
                statement.setString(3, ign);
                statement.setString(4, ign.toLowerCase(java.util.Locale.ROOT));
                statement.setLong(5, removedAt);
                statement.executeUpdate();
            }
            return null;
        });
    }

    /** Forgets permanent removals for this Discord ID or Minecraft name: they were accepted back. */
    public CompletableFuture<Integer> clearRemovals(String discordId, String ign) {
        return database.submit(c -> {
            try (PreparedStatement statement = c.prepareStatement(
                    "DELETE FROM removed_members WHERE discord_id = ? OR ign_key = ?")) {
                statement.setString(1, discordId == null ? "" : discordId);
                statement.setString(2, ign == null ? "" : ign.toLowerCase(java.util.Locale.ROOT));
                return statement.executeUpdate();
            }
        });
    }

    /** A permanent removal matching this Discord ID or Minecraft name (either may be null). */
    public CompletableFuture<java.util.Optional<RemovedMember>> findRemoval(String discordId, String ign) {
        return database.submit(c -> {
            try (PreparedStatement statement = c.prepareStatement(
                    "SELECT discord_id, ign, removed_at FROM removed_members WHERE discord_id = ? OR ign_key = ?"
                            + " ORDER BY removed_at DESC LIMIT 1")) {
                statement.setString(1, discordId == null ? "" : discordId);
                statement.setString(2, ign == null ? "" : ign.toLowerCase(java.util.Locale.ROOT));
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next()
                            ? java.util.Optional.of(new RemovedMember(rs.getString("discord_id"), rs.getString("ign"), rs.getLong("removed_at")))
                            : java.util.Optional.empty();
                }
            }
        });
    }

    // --- weekly stat snapshots ----------------------------------------------------------------------

    public CompletableFuture<Void> saveSnapshot(long takenAt, Map<UUID, Counters> counters) {
        return database.submit(c -> {
            try (PreparedStatement statement = c.prepareStatement("""
                    INSERT OR REPLACE INTO stat_snapshots
                        (uuid, taken_at, play_ticks, deaths, mob_kills, player_kills, blocks_mined, walked_cm)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
                for (Map.Entry<UUID, Counters> entry : counters.entrySet()) {
                    Counters value = entry.getValue();
                    statement.setString(1, entry.getKey().toString());
                    statement.setLong(2, takenAt);
                    statement.setLong(3, value.playTicks());
                    statement.setLong(4, value.deaths());
                    statement.setLong(5, value.mobKills());
                    statement.setLong(6, value.playerKills());
                    statement.setLong(7, value.blocksMined());
                    statement.setLong(8, value.walkedCm());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            // Only the last couple of months are ever compared against.
            try (PreparedStatement prune = c.prepareStatement("DELETE FROM stat_snapshots WHERE taken_at < ?")) {
                prune.setLong(1, takenAt - 62L * 24 * 60 * 60 * 1000);
                prune.executeUpdate();
            }
            return null;
        });
    }

    /** The newest snapshot taken at or before {@code atOrBefore}, or null when there is none. */
    public CompletableFuture<Snapshot> snapshotBefore(long atOrBefore) {
        return database.submit(c -> {
            long takenAt;
            try (PreparedStatement statement = c.prepareStatement(
                    "SELECT MAX(taken_at) AS t FROM stat_snapshots WHERE taken_at <= ?")) {
                statement.setLong(1, atOrBefore);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    takenAt = rs.getLong("t");
                    if (rs.wasNull()) {
                        return null;
                    }
                }
            }
            Map<UUID, Counters> counters = new HashMap<>();
            try (PreparedStatement statement = c.prepareStatement("SELECT * FROM stat_snapshots WHERE taken_at = ?")) {
                statement.setLong(1, takenAt);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        counters.put(UUID.fromString(rs.getString("uuid")), new Counters(
                                rs.getLong("play_ticks"), rs.getLong("deaths"), rs.getLong("mob_kills"),
                                rs.getLong("player_kills"), rs.getLong("blocks_mined"), rs.getLong("walked_cm")));
                    }
                }
            }
            return new Snapshot(takenAt, counters);
        });
    }
}
