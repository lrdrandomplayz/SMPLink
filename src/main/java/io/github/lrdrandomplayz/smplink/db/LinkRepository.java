package io.github.lrdrandomplayz.smplink.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public final class LinkRepository {

    /** {@code original} marks players from the sheet's first table, who are exempt from activity checks. */
    public record Link(UUID uuid, String discordId, String name, long linkedAt, boolean original) {
    }

    public record ImportEntry(String discordId, UUID uuid, String name, boolean original) {
    }

    public record Change(String action, String discordId, UUID uuid, String name) {
    }

    public record Conflict(String discordId, UUID uuid, String name, String reason) {
    }

    /** {@code skipped} counts both unchanged rows and conflicts; {@code unchanged} breaks out the harmless part. */
    public record ImportResult(int inserted, int updated, int skipped, int unchanged,
                               List<Change> changes, List<Conflict> conflicts) {
    }

    private static final String SELECT = "SELECT uuid, discord_id, name, linked_at, original FROM links ";

    private final Database database;
    private final Logger logger;

    public LinkRepository(Database database, Logger logger) {
        this.database = database;
        this.logger = logger;
    }

    public CompletableFuture<Optional<Link>> findByDiscord(String discordId) {
        return database.submit(c -> selectOne(c, SELECT + "WHERE discord_id = ?", discordId));
    }

    public CompletableFuture<Optional<Link>> findByUuid(UUID uuid) {
        return database.submit(c -> selectOne(c, SELECT + "WHERE uuid = ?", uuid.toString()));
    }

    public CompletableFuture<Optional<Link>> findByName(String name) {
        return database.submit(c -> selectOne(c, SELECT + "WHERE name = ? COLLATE NOCASE", name));
    }

    public CompletableFuture<List<Link>> findAll() {
        return database.submit(c -> {
            List<Link> all = new ArrayList<>();
            try (PreparedStatement statement = c.prepareStatement(SELECT);
                 ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    all.add(read(rs));
                }
            }
            return all;
        });
    }

    /** {@code skippedReason} is set when the safety limit stopped the removal; nothing is deleted then. */
    public record Removal(List<Link> removed, String skippedReason) {
    }

    /**
     * Deletes links whose Discord ID and Minecraft name no longer appear anywhere in the sheet. Matching
     * on either keeps players whose username lookup failed this run. If more than {@code maxFraction}
     * of all links would go at once, nothing is removed: that usually means a broken or emptied sheet.
     */
    public CompletableFuture<Removal> removeMissing(Set<String> sheetDiscordIds, Set<String> sheetNameKeys,
                                                    double maxFraction) {
        return database.submit(c -> {
            List<Link> all = new ArrayList<>();
            try (PreparedStatement statement = c.prepareStatement(SELECT); ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    all.add(read(rs));
                }
            }
            List<Link> gone = all.stream()
                    .filter(link -> !sheetDiscordIds.contains(link.discordId()))
                    .filter(link -> link.name() == null || !sheetNameKeys.contains(link.name().toLowerCase(Locale.ROOT)))
                    .toList();
            if (gone.isEmpty()) {
                return new Removal(List.of(), null);
            }
            if (gone.size() > Math.max(3, all.size() * maxFraction)) {
                String reason = "the sheet no longer lists " + gone.size() + " of " + all.size()
                        + " linked players, which looks like a broken or emptied sheet, so nobody was unlinked";
                logger.warning("[import] " + reason);
                return new Removal(List.of(), reason);
            }
            c.setAutoCommit(false);
            try {
                for (Link link : gone) {
                    update(c, "DELETE FROM links WHERE uuid = ?", link.uuid().toString());
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
            for (Link link : gone) {
                logger.info("[import] unlinked (removed from sheet) discord=" + link.discordId() + " uuid="
                        + link.uuid() + " name=" + link.name());
            }
            return new Removal(gone, null);
        });
    }

    /** Removes the link of one Discord account (/unlink); empty if it wasn't linked. */
    public CompletableFuture<Optional<Link>> removeByDiscord(String discordId) {
        return database.submit(c -> {
            Optional<Link> existing = selectOne(c, SELECT + "WHERE discord_id = ?", discordId);
            if (existing.isPresent()) {
                update(c, "DELETE FROM links WHERE discord_id = ?", discordId);
                logger.info("[link] unlinked discord=" + discordId + " uuid=" + existing.get().uuid() + " name=" + existing.get().name());
            }
            return existing;
        });
    }

    public CompletableFuture<ImportResult> importEntries(List<ImportEntry> entries, boolean allowRelinking) {
        return importEntries(entries, allowRelinking, "import");
    }

    /** {@code source} names what made the change in the console log ("import" for the sheet, "link" for /link). */
    public CompletableFuture<ImportResult> importEntries(List<ImportEntry> entries, boolean allowRelinking, String source) {
        return database.submit(c -> {
            c.setAutoCommit(false);
            try {
                ImportResult result = applyImport(c, entries, allowRelinking);
                c.commit();
                // Logged after commit so the console never reports a change that was rolled back.
                for (Change change : result.changes()) {
                    logger.info("[" + source + "] " + change.action() + " discord=" + change.discordId()
                            + " uuid=" + change.uuid() + " name=" + change.name());
                }
                for (Conflict conflict : result.conflicts()) {
                    logger.warning("[" + source + "] skipped discord=" + conflict.discordId() + " uuid=" + conflict.uuid()
                            + " name=" + conflict.name() + ": " + conflict.reason());
                }
                if (!source.equals("import")) {
                    return result;
                }
                logger.info("[import] " + entries.size() + " entries: " + result.inserted() + " inserted, "
                        + result.updated() + " updated, " + result.skipped() + " skipped ("
                        + result.unchanged() + " unchanged)");
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        });
    }

    private ImportResult applyImport(Connection c, List<ImportEntry> entries, boolean allowRelinking) throws SQLException {
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        List<Change> changes = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (ImportEntry entry : entries) {
            Optional<Link> byUuid = selectOne(c, SELECT + "WHERE uuid = ?", entry.uuid().toString());
            Optional<Link> byDiscord = selectOne(c, SELECT + "WHERE discord_id = ?", entry.discordId());

            if (byUuid.isPresent() && byUuid.get().discordId().equals(entry.discordId())) {
                Link existing = byUuid.get();
                boolean renamed = !Objects.equals(existing.name(), entry.name());
                boolean regrouped = existing.original() != entry.original();
                if (!renamed && !regrouped) {
                    unchanged++;
                    continue;
                }
                try (PreparedStatement statement = c.prepareStatement(
                        "UPDATE links SET name = ?, original = ? WHERE uuid = ?")) {
                    statement.setString(1, entry.name());
                    statement.setInt(2, entry.original() ? 1 : 0);
                    statement.setString(3, entry.uuid().toString());
                    statement.executeUpdate();
                }
                String action = renamed ? "renamed" : entry.original() ? "marked original member" : "marked new member";
                changes.add(new Change(action, entry.discordId(), entry.uuid(), entry.name()));
                updated++;
                continue;
            }

            if (byUuid.isEmpty() && byDiscord.isEmpty()) {
                insert(c, entry, now);
                changes.add(new Change("linked", entry.discordId(), entry.uuid(), entry.name()));
                inserted++;
                continue;
            }

            if (!allowRelinking) {
                String reason = byDiscord.isPresent()
                        ? "Discord ID is already linked to " + byDiscord.get().uuid()
                        : "UUID is already linked to Discord ID " + byUuid.get().discordId();
                conflicts.add(new Conflict(entry.discordId(), entry.uuid(), entry.name(), reason));
                continue;
            }

            for (Link old : List.of(byUuid, byDiscord).stream().flatMap(Optional::stream).distinct().toList()) {
                update(c, "DELETE FROM links WHERE uuid = ?", old.uuid().toString());
                changes.add(new Change("unlinked", old.discordId(), old.uuid(), old.name()));
            }
            insert(c, entry, now);
            changes.add(new Change("relinked", entry.discordId(), entry.uuid(), entry.name()));
            updated++;
        }

        int skipped = unchanged + conflicts.size();
        return new ImportResult(inserted, updated, skipped, unchanged, changes, conflicts);
    }

    private static void insert(Connection c, ImportEntry entry, long now) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(
                "INSERT INTO links (uuid, discord_id, name, linked_at, original) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, entry.uuid().toString());
            statement.setString(2, entry.discordId());
            statement.setString(3, entry.name());
            statement.setLong(4, now);
            statement.setInt(5, entry.original() ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private static void update(Connection c, String sql, String... params) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setString(i + 1, params[i]);
            }
            statement.executeUpdate();
        }
    }

    private static Optional<Link> selectOne(Connection c, String sql, String param) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setString(1, param);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    private static Link read(ResultSet rs) throws SQLException {
        return new Link(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("discord_id"),
                rs.getString("name"),
                rs.getLong("linked_at"),
                rs.getInt("original") == 1);
    }
}
