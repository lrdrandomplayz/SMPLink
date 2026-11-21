package io.github.lrdrandomplayz.smplink.crash;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import io.github.lrdrandomplayz.smplink.crash.CrashCase.MissingItem;
import io.github.lrdrandomplayz.smplink.crash.CrashCase.Pos;
import io.github.lrdrandomplayz.smplink.db.Database;

/** The crash_cases table, so a restart doesn't lose cases staff haven't dealt with. Owns its table. */
public final class CrashCaseRepository {

    private final Database database;

    public CrashCaseRepository(Database database) {
        this.database = database;
    }

    public CompletableFuture<Void> init() {
        return database.submit(c -> {
            try (Statement statement = c.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS crash_cases (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            uuid TEXT NOT NULL,
                            name TEXT,
                            crash_time INTEGER NOT NULL,
                            status TEXT NOT NULL,
                            save_time INTEGER,
                            save_uncertain INTEGER,
                            missing TEXT,
                            real_world TEXT, rx REAL, ry REAL, rz REAL,
                            join_world TEXT, jx REAL, jy REAL, jz REAL,
                            moved REAL,
                            dupes TEXT,
                            gave INTEGER NOT NULL DEFAULT 0,
                            tped INTEGER NOT NULL DEFAULT 0,
                            give_pending INTEGER NOT NULL DEFAULT 0,
                            log TEXT
                        )""");
                statement.execute("CREATE INDEX IF NOT EXISTS crash_cases_uuid ON crash_cases (uuid, status)");
            }
            return null;
        });
    }

    /** Records that this player was online at the crash and hasn't rejoined; returns the case ID. */
    public CompletableFuture<Long> addAwaiting(UUID uuid, String name, long crashTime) {
        return database.submit(c -> {
            try (PreparedStatement statement = c.prepareStatement(
                    "INSERT INTO crash_cases (uuid, name, crash_time, status) VALUES (?, ?, ?, 'awaiting')",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, name);
                statement.setLong(3, crashTime);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    /** The newest case for this player still waiting for them to rejoin. */
    public CompletableFuture<Optional<CrashCase>> awaiting(UUID uuid) {
        return one("WHERE uuid = ? AND status = 'awaiting' ORDER BY id DESC LIMIT 1", uuid.toString());
    }

    public CompletableFuture<Optional<CrashCase>> get(long id) {
        return one("WHERE id = ?", id);
    }

    /** Cases whose items should be handed over the next time the player is online. */
    public CompletableFuture<List<CrashCase>> givePending(UUID uuid) {
        return database.submit(c -> {
            List<CrashCase> out = new ArrayList<>();
            try (PreparedStatement statement = c.prepareStatement(
                    SELECT + "WHERE uuid = ? AND give_pending = 1 AND gave = 0")) {
                statement.setString(1, uuid.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        out.add(read(rs));
                    }
                }
            }
            return out;
        });
    }

    /** Cases still open for staff (to repost cards after a restart if ever needed). */
    public CompletableFuture<List<CrashCase>> open() {
        return database.submit(c -> {
            List<CrashCase> out = new ArrayList<>();
            try (Statement statement = c.createStatement();
                 ResultSet rs = statement.executeQuery(SELECT + "WHERE status = 'open' ORDER BY id")) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
            return out;
        });
    }

    /** Writes every field of the case back. */
    public CompletableFuture<Void> save(CrashCase k) {
        return database.submit(c -> {
            try (PreparedStatement statement = c.prepareStatement("""
                    UPDATE crash_cases SET name = ?, crash_time = ?, status = ?, save_time = ?, save_uncertain = ?,
                        missing = ?, real_world = ?, rx = ?, ry = ?, rz = ?, join_world = ?, jx = ?, jy = ?, jz = ?,
                        moved = ?, dupes = ?, gave = ?, tped = ?, give_pending = ?, log = ?
                    WHERE id = ?""")) {
                statement.setString(1, k.name());
                statement.setLong(2, k.crashTime());
                statement.setString(3, k.status());
                statement.setLong(4, k.saveTime());
                statement.setInt(5, k.saveUncertain() ? 1 : 0);
                statement.setString(6, encodeMissing(k.missing()));
                Pos r = k.real();
                statement.setString(7, r == null ? null : r.world());
                statement.setDouble(8, r == null ? 0 : r.x());
                statement.setDouble(9, r == null ? 0 : r.y());
                statement.setDouble(10, r == null ? 0 : r.z());
                Pos j = k.join();
                statement.setString(11, j == null ? null : j.world());
                statement.setDouble(12, j == null ? 0 : j.x());
                statement.setDouble(13, j == null ? 0 : j.y());
                statement.setDouble(14, j == null ? 0 : j.z());
                statement.setDouble(15, k.moved());
                statement.setString(16, String.join("\n", k.dupeWarnings()));
                statement.setInt(17, k.gave() ? 1 : 0);
                statement.setInt(18, k.tped() ? 1 : 0);
                statement.setInt(19, k.givePending() ? 1 : 0);
                statement.setString(20, k.log());
                statement.setLong(21, k.id());
                statement.executeUpdate();
            }
            return null;
        });
    }

    /** Deletes cases whose crash was before {@code time}; returns how many. */
    public CompletableFuture<Integer> deleteOlderThan(long time) {
        return database.submit(c -> {
            try (PreparedStatement statement = c.prepareStatement("DELETE FROM crash_cases WHERE crash_time < ?")) {
                statement.setLong(1, time);
                return statement.executeUpdate();
            }
        });
    }

    private static final String SELECT = """
            SELECT id, uuid, name, crash_time, status, save_time, save_uncertain, missing, real_world, rx, ry, rz,
                   join_world, jx, jy, jz, moved, dupes, gave, tped, give_pending, log FROM crash_cases\s""";

    private CompletableFuture<Optional<CrashCase>> one(String where, Object arg) {
        return database.submit(c -> {
            try (PreparedStatement statement = c.prepareStatement(SELECT + where)) {
                if (arg instanceof Long l) {
                    statement.setLong(1, l);
                } else {
                    statement.setString(1, String.valueOf(arg));
                }
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.<CrashCase>empty();
                }
            }
        });
    }

    private static CrashCase read(ResultSet rs) throws SQLException {
        String realWorld = rs.getString("real_world");
        String joinWorld = rs.getString("join_world");
        String dupes = rs.getString("dupes");
        String log = rs.getString("log");
        return new CrashCase(rs.getLong("id"), UUID.fromString(rs.getString("uuid")), rs.getString("name"),
                rs.getLong("save_time"), rs.getInt("save_uncertain") == 1, rs.getLong("crash_time"),
                decodeMissing(rs.getString("missing")),
                realWorld == null ? null : new Pos(realWorld, rs.getDouble("rx"), rs.getDouble("ry"), rs.getDouble("rz")),
                joinWorld == null ? null : new Pos(joinWorld, rs.getDouble("jx"), rs.getDouble("jy"), rs.getDouble("jz")),
                rs.getDouble("moved"),
                dupes == null || dupes.isEmpty() ? List.of() : Arrays.asList(dupes.split("\n")),
                rs.getString("status"), rs.getInt("gave") == 1, rs.getInt("tped") == 1,
                rs.getInt("give_pending") == 1, log == null ? "" : log);
    }

    // One item per line: amount TAB base64(data) TAB name. Names never contain tabs or newlines after clean().
    static String encodeMissing(List<MissingItem> items) {
        StringBuilder out = new StringBuilder();
        for (MissingItem item : items) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(item.amount()).append('\t').append(item.data()).append('\t').append(clean(item.name()));
        }
        return out.toString();
    }

    static List<MissingItem> decodeMissing(String text) {
        List<MissingItem> items = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return items;
        }
        for (String line : text.split("\n")) {
            String[] part = line.split("\t", 3);
            if (part.length == 3) {
                items.add(new MissingItem(part[2], Integer.parseInt(part[0]), part[1]));
            }
        }
        return items;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ');
    }
}
