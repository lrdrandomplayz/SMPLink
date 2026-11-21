package io.github.lrdrandomplayz.smplink.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Database {

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private final Path file;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SMPLink-DB");
        thread.setDaemon(true);
        return thread;
    });

    // Only ever read or written on the executor thread, which is what makes a single connection safe.
    private Connection connection;

    public Database(Path file) {
        this.file = file;
    }

    public CompletableFuture<Void> open() {
        return submit(ignored -> {
            try {
                Files.createDirectories(file.getParent());
                Class.forName("org.sqlite.JDBC");
            } catch (IOException | ClassNotFoundException e) {
                throw new SQLException("Could not prepare " + file, e);
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS links (
                            uuid TEXT PRIMARY KEY,
                            discord_id TEXT UNIQUE,
                            name TEXT,
                            linked_at INTEGER
                        )""");
                statement.execute("CREATE INDEX IF NOT EXISTS links_name ON links (name COLLATE NOCASE)");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS inv_cache (
                            uuid TEXT PRIMARY KEY,
                            inventory BLOB,
                            armor BLOB,
                            ender BLOB,
                            updated_at INTEGER
                        )""");
                // Vanilla only keeps an all-time playtime counter, so weekly and monthly playtime
                // come from these sessions, recorded from the moment this table first existed.
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS sessions (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            uuid TEXT NOT NULL,
                            started_at INTEGER NOT NULL,
                            ended_at INTEGER NOT NULL
                        )""");
                statement.execute("CREATE INDEX IF NOT EXISTS sessions_ended ON sessions (ended_at)");
                statement.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)");
                statement.execute("INSERT OR IGNORE INTO meta (key, value) VALUES ('tracking_started', "
                        + System.currentTimeMillis() + ")");
                // Names the plugin itself whitelisted, so a sync only ever removes names it added.
                statement.execute("CREATE TABLE IF NOT EXISTS whitelist_sync (name_key TEXT PRIMARY KEY, name TEXT NOT NULL)");
                // Milestones already reached; players are recorded in milestone_seen the first time they're
                // checked so hours earned before the feature existed are never announced.
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS milestones (
                            uuid TEXT NOT NULL,
                            hours INTEGER NOT NULL,
                            reached_at INTEGER NOT NULL,
                            announced INTEGER NOT NULL,
                            PRIMARY KEY (uuid, hours)
                        )""");
                statement.execute("CREATE TABLE IF NOT EXISTS milestone_seen (uuid TEXT PRIMARY KEY)");
                // Activity-check warnings: the quota counts playtime from window_start up to the deadline.
                // status is pending until the player passes, is removed, or the warning no longer applies.
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS activity_warnings (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            discord_id TEXT NOT NULL,
                            uuid TEXT NOT NULL,
                            ign TEXT NOT NULL,
                            window_start INTEGER NOT NULL,
                            warned_at INTEGER NOT NULL,
                            deadline INTEGER NOT NULL,
                            status TEXT NOT NULL,
                            resolved_at INTEGER
                        )""");
                // Weekly copies of the all-time vanilla counters, so the recap can show a week's kills and mining.
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS stat_snapshots (
                            uuid TEXT NOT NULL,
                            taken_at INTEGER NOT NULL,
                            play_ticks INTEGER NOT NULL,
                            deaths INTEGER NOT NULL,
                            mob_kills INTEGER NOT NULL,
                            player_kills INTEGER NOT NULL,
                            blocks_mined INTEGER NOT NULL,
                            walked_cm INTEGER NOT NULL,
                            PRIMARY KEY (uuid, taken_at)
                        )""");
                // Strikes: kind is activity (a missed activity-check deadline, with its warning_id), raid (offline
                // raiding, from /check raid) or manual (/strike add). They expire after strikes.expire-days and
                // can be taken back with /strike remove, which sets removed_at instead of deleting the row.
                if (hasTable(connection, "strikes") && !hasColumn(connection, "strikes", "kind")) {
                    // 1.13-1.16 only had activity strikes, with a required warning_id.
                    statement.execute("ALTER TABLE strikes RENAME TO strikes_v1");
                }
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS strikes (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            discord_id TEXT NOT NULL,
                            uuid TEXT NOT NULL,
                            ign TEXT NOT NULL,
                            kind TEXT NOT NULL,
                            reason TEXT NOT NULL,
                            given_by TEXT NOT NULL,
                            warning_id INTEGER UNIQUE,
                            given_at INTEGER NOT NULL,
                            removed_at INTEGER,
                            removed_by TEXT
                        )""");
                if (hasTable(connection, "strikes_v1")) {
                    statement.execute("""
                            INSERT INTO strikes (id, discord_id, uuid, ign, kind, reason, given_by, warning_id, given_at)
                            SELECT id, discord_id, uuid, ign, 'activity', 'Missed the activity-check deadline',
                                   'the activity check', warning_id, given_at FROM strikes_v1""");
                    statement.execute("DROP TABLE strikes_v1");
                }
                statement.execute("CREATE INDEX IF NOT EXISTS strikes_member ON strikes (discord_id, given_at)");
                // Members permanently removed for too many strikes; /whitelist add refuses them.
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS removed_members (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            discord_id TEXT NOT NULL,
                            uuid TEXT NOT NULL,
                            ign TEXT NOT NULL,
                            ign_key TEXT NOT NULL,
                            removed_at INTEGER NOT NULL
                        )""");
                // Posts made with /trade; status is open until marked traded, removed or expired.
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS trades (
                            message_id TEXT PRIMARY KEY,
                            channel_id TEXT NOT NULL,
                            discord_id TEXT NOT NULL,
                            ign TEXT NOT NULL,
                            created_at INTEGER NOT NULL,
                            status TEXT NOT NULL
                        )""");
                // Added in 1.12.0: the post's text and items (for /trades) and who it was traded with.
                for (String column : new String[]{"offering", "wanted", "offer_items", "want_items", "partner_id"}) {
                    if (!hasColumn(connection, "trades", column)) {
                        statement.execute("ALTER TABLE trades ADD COLUMN " + column + " TEXT");
                    }
                }
                // Added in 1.19.0: the partner confirms the trade happened before either can rate the other.
                // NULL = waiting for them, 1 = yes, 0 = no. Trades completed before this are taken as confirmed.
                if (!hasColumn(connection, "trades", "confirmed")) {
                    statement.execute("ALTER TABLE trades ADD COLUMN confirmed INTEGER");
                    statement.execute("UPDATE trades SET confirmed = 1 WHERE status = 'traded' AND partner_id IS NOT NULL");
                }
                // Added in 1.20.0: who pressed Request Middleman on the post, the middleman who claimed it and the
                // private thread opened for it (each null until it happens).
                for (String column : new String[]{"middleman_by", "middleman_id", "thread_id"}) {
                    if (!hasColumn(connection, "trades", column)) {
                        statement.execute("ALTER TABLE trades ADD COLUMN " + column + " TEXT");
                    }
                }
                // Added in 1.22.0: whether thread_id is a thread opened just for the middleman (1) or a buyer's
                // negotiation thread they were added to (0); how many times one was requested (at most 2); who last
                // cancelled one; and whether the poster said the middleman handled the trade (null until asked).
                for (String[] column : new String[][]{{"thread_dedicated", "INTEGER"}, {"middleman_requests", "INTEGER NOT NULL DEFAULT 0"},
                        {"middleman_cancelled_by", "TEXT"}, {"middleman_used", "INTEGER"}}) {
                    if (!hasColumn(connection, "trades", column[0])) {
                        statement.execute("ALTER TABLE trades ADD COLUMN " + column[0] + " " + column[1]);
                    }
                }
                // Members who pressed "I'm interested" on a trade post.
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS trade_interest (
                            message_id TEXT NOT NULL,
                            discord_id TEXT NOT NULL,
                            ign TEXT NOT NULL,
                            at INTEGER NOT NULL,
                            PRIMARY KEY (message_id, discord_id)
                        )""");
                // Added in 1.22.0: the private negotiation thread opened for each interested member.
                if (!hasColumn(connection, "trade_interest", "thread_id")) {
                    statement.execute("ALTER TABLE trade_interest ADD COLUMN thread_id TEXT");
                }
                // What each trader thought of the other (kind 'trade') and of the trade's middleman (kind 'middleman')
                // after a confirmed trade: vouch = 1, scam report = 0.
                if (hasTable(connection, "trade_ratings") && !hasColumn(connection, "trade_ratings", "kind")) {
                    // 1.12-1.19 only rated the other trader, one rating per trade and rater.
                    statement.execute("ALTER TABLE trade_ratings RENAME TO trade_ratings_v1");
                }
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS trade_ratings (
                            message_id TEXT NOT NULL,
                            rater_id TEXT NOT NULL,
                            target_id TEXT NOT NULL,
                            kind TEXT NOT NULL,
                            vouch INTEGER NOT NULL,
                            rated_at INTEGER NOT NULL,
                            PRIMARY KEY (message_id, rater_id, kind)
                        )""");
                if (hasTable(connection, "trade_ratings_v1")) {
                    statement.execute("""
                            INSERT INTO trade_ratings (message_id, rater_id, target_id, kind, vouch, rated_at)
                            SELECT message_id, rater_id, target_id, 'trade', vouch, rated_at FROM trade_ratings_v1""");
                    statement.execute("DROP TABLE trade_ratings_v1");
                }
                statement.execute("CREATE INDEX IF NOT EXISTS trade_ratings_target ON trade_ratings (target_id)");
                // Small named values that must survive restarts (the activity check's pause, one-off migrations).
                statement.execute("CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                // 1 = listed in the sheet's first table (original members), who are exempt from activity checks.
                if (!hasColumn(connection, "links", "original")) {
                    statement.execute("ALTER TABLE links ADD COLUMN original INTEGER NOT NULL DEFAULT 0");
                }
            }
            return null;
        });
    }

    /** Writes a consistent copy of the database to {@code target}, which must not exist yet. */
    public CompletableFuture<Void> backupTo(Path target) {
        return submit(c -> {
            try (Statement statement = c.createStatement()) {
                // VACUUM INTO takes a snapshot inside SQLite, so the copy is never half-written mid-transaction.
                statement.execute("VACUUM INTO '" + target.toAbsolutePath().toString().replace("'", "''") + "'");
            }
            return null;
        });
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             java.sql.ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (rs.getString("name").equalsIgnoreCase(column)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasTable(Connection connection, String table) throws SQLException {
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (java.sql.ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    public <T> CompletableFuture<T> submit(SqlWork<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.run(connection);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    public void close() {
        executor.execute(() -> {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // Nothing useful to do while shutting down.
                }
            }
        });
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
