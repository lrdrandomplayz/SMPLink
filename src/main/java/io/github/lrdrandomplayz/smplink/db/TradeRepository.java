package io.github.lrdrandomplayz.smplink.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Trade posts in the Discord trade channel and the ratings traders give each other afterwards.
 * Items are stored as "diamond:64,enchanted_book:1".
 */
public final class TradeRepository {

    /**
     * {@code partnerId} is set once the poster marks it traded with someone; status is open, traded,
     * removed, expired or gone. {@code confirmed} is whether the partner said the trade happened: null
     * while they haven't answered.
     */
    public record Trade(String messageId, String channelId, String discordId, String ign, long createdAt,
                        String offering, String wanted, String offerItems, String wantItems,
                        String status, String partnerId, Boolean confirmed, String middlemanBy, String middlemanId,
                        String threadId, boolean threadDedicated, int middlemanRequests, String middlemanCancelledBy,
                        Boolean middlemanUsed) {

        public static Trade open(String messageId, String channelId, String discordId, String ign, long createdAt,
                                 String offering, String wanted, String offerItems, String wantItems) {
            return new Trade(messageId, channelId, discordId, ign, createdAt, offering, wanted, offerItems, wantItems,
                    "open", null, null, null, null, null, false, 0, null, null);
        }
    }

    /** Someone who pressed "I'm interested" on a post, and the private thread opened for them (null if none). */
    public record Interest(String discordId, String ign, long at, String threadId) {
    }

    /** Rep counts each member once, by their latest rating: +1 for a vouch, -1 for a scam report. */
    public record Rep(int vouches, int scamReports, int trades) {
        public int score() {
            return vouches - scamReports;
        }
    }

    private static final String COLUMNS = "message_id, channel_id, discord_id, ign, created_at, offering, wanted,"
            + " offer_items, want_items, status, partner_id, confirmed, middleman_by, middleman_id, thread_id,"
            + " thread_dedicated, middleman_requests, middleman_cancelled_by, middleman_used";

    private final Database database;

    public TradeRepository(Database database) {
        this.database = database;
    }

    public CompletableFuture<Void> add(Trade trade) {
        return database.submit(c -> {
            try (PreparedStatement insert = c.prepareStatement("INSERT OR REPLACE INTO trades (" + COLUMNS
                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL)")) {
                insert.setString(1, trade.messageId());
                insert.setString(2, trade.channelId());
                insert.setString(3, trade.discordId());
                insert.setString(4, trade.ign());
                insert.setLong(5, trade.createdAt());
                insert.setString(6, trade.offering());
                insert.setString(7, trade.wanted());
                insert.setString(8, trade.offerItems());
                insert.setString(9, trade.wantItems());
                insert.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Optional<Trade>> find(String messageId) {
        return database.submit(c -> {
            List<Trade> found = select(c, "SELECT " + COLUMNS + " FROM trades WHERE message_id = ?", messageId);
            return found.stream().findFirst();
        });
    }

    /** Marks an open trade removed or expired; returns false if it was already closed. */
    public CompletableFuture<Boolean> close(String messageId, String status) {
        return database.submit(c -> {
            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE trades SET status = ? WHERE message_id = ? AND status = 'open'")) {
                update.setString(1, status);
                update.setString(2, messageId);
                return update.executeUpdate() > 0;
            }
        });
    }

    /**
     * The partner's answer to "did you trade?". Only counts for a traded trade they haven't answered for
     * yet; returns false otherwise.
     */
    public CompletableFuture<Boolean> confirm(String messageId, boolean happened) {
        return database.submit(c -> {
            try (PreparedStatement update = c.prepareStatement("UPDATE trades SET confirmed = ? WHERE message_id = ?"
                    + " AND status = 'traded' AND partner_id IS NOT NULL AND confirmed IS NULL")) {
                update.setInt(1, happened ? 1 : 0);
                update.setString(2, messageId);
                return update.executeUpdate() > 0;
            }
        });
    }

    /** How many times a middleman can be requested on one trade, so the role can't be pinged over and over. */
    public static final int MAX_MIDDLEMAN_REQUESTS = 2;

    /**
     * Records who asked for a middleman on an open post; false if one is already requested, it's closed, or
     * the trade has used up its {@link #MAX_MIDDLEMAN_REQUESTS}.
     */
    public CompletableFuture<Boolean> requestMiddleman(String messageId, String discordId) {
        return database.submit(c -> {
            try (PreparedStatement update = c.prepareStatement("UPDATE trades SET middleman_by = ?, middleman_requests = middleman_requests + 1"
                    + " WHERE message_id = ? AND status = 'open' AND middleman_by IS NULL AND middleman_requests < "
                    + MAX_MIDDLEMAN_REQUESTS)) {
                update.setString(1, discordId);
                update.setString(2, messageId);
                return update.executeUpdate() > 0;
            }
        });
    }

    /** A middleman takes on an open trade that asked for one; false if someone already has, or it's closed. */
    public CompletableFuture<Boolean> claimMiddleman(String messageId, String middlemanId) {
        return database.submit(c -> {
            try (PreparedStatement update = c.prepareStatement("UPDATE trades SET middleman_id = ? WHERE message_id = ?"
                    + " AND status = 'open' AND middleman_by IS NOT NULL AND middleman_id IS NULL")) {
                update.setString(1, middlemanId);
                update.setString(2, messageId);
                return update.executeUpdate() > 0;
            }
        });
    }

    /** The middleman's thread: one opened just for them ({@code dedicated}) or a buyer's negotiation thread. */
    public CompletableFuture<Void> setThread(String messageId, String threadId, boolean dedicated) {
        return database.submit(c -> {
            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE trades SET thread_id = ?, thread_dedicated = ? WHERE message_id = ?")) {
                update.setString(1, threadId);
                update.setInt(2, dedicated ? 1 : 0);
                update.setString(3, messageId);
                update.executeUpdate();
            }
            return null;
        });
    }

    /** Drops the middleman from an open trade; false if there's none to drop. */
    public CompletableFuture<Boolean> cancelMiddleman(String messageId, String cancelledBy) {
        return database.submit(c -> {
            try (PreparedStatement update = c.prepareStatement("UPDATE trades SET middleman_by = NULL, middleman_id = NULL,"
                    + " thread_id = NULL, thread_dedicated = NULL, middleman_cancelled_by = ?"
                    + " WHERE message_id = ? AND status = 'open' AND middleman_by IS NOT NULL")) {
                update.setString(1, cancelledBy);
                update.setString(2, messageId);
                return update.executeUpdate() > 0;
            }
        });
    }

    /** The poster's answer to "did the middleman handle this trade?". */
    public CompletableFuture<Void> setMiddlemanUsed(String messageId, boolean used) {
        return database.submit(c -> {
            try (PreparedStatement update = c.prepareStatement("UPDATE trades SET middleman_used = ? WHERE message_id = ?")) {
                update.setInt(1, used ? 1 : 0);
                update.setString(2, messageId);
                update.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> setInterestThread(String messageId, String discordId, String threadId) {
        return database.submit(c -> {
            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE trade_interest SET thread_id = ? WHERE message_id = ? AND discord_id = ?")) {
                update.setString(1, threadId);
                update.setString(2, messageId);
                update.setString(3, discordId);
                update.executeUpdate();
            }
            return null;
        });
    }

    /** Records that a member is interested in a post; false if they already were. */
    public CompletableFuture<Boolean> addInterest(String messageId, String discordId, String ign, long at) {
        return database.submit(c -> {
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT OR IGNORE INTO trade_interest (message_id, discord_id, ign, at) VALUES (?, ?, ?, ?)")) {
                insert.setString(1, messageId);
                insert.setString(2, discordId);
                insert.setString(3, ign);
                insert.setLong(4, at);
                return insert.executeUpdate() > 0;
            }
        });
    }

    /** Everyone interested in a post, first first. */
    public CompletableFuture<List<Interest>> interested(String messageId) {
        return database.submit(c -> {
            List<Interest> interested = new ArrayList<>();
            try (PreparedStatement select = c.prepareStatement(
                    "SELECT discord_id, ign, at, thread_id FROM trade_interest WHERE message_id = ? ORDER BY at")) {
                select.setString(1, messageId);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        interested.add(new Interest(rs.getString("discord_id"), rs.getString("ign"), rs.getLong("at"),
                                rs.getString("thread_id")));
                    }
                }
            }
            return interested;
        });
    }

    /** Marks an open trade traded, with the member it was traded with (null if not given). */
    public CompletableFuture<Boolean> complete(String messageId, String partnerId) {
        return database.submit(c -> {
            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE trades SET status = 'traded', partner_id = ? WHERE message_id = ? AND status = 'open'")) {
                update.setString(1, partnerId);
                update.setString(2, messageId);
                return update.executeUpdate() > 0;
            }
        });
    }

    /** Posts still on the channel (open, traded or expired) made after {@code since}, oldest first. */
    public CompletableFuture<List<Trade>> postsSince(long since) {
        return database.submit(c -> select(c, "SELECT " + COLUMNS + " FROM trades"
                + " WHERE status IN ('open', 'traded', 'expired') AND created_at > ? ORDER BY created_at", since));
    }

    public CompletableFuture<List<Trade>> openOlderThan(long createdBefore) {
        return database.submit(c -> select(c, "SELECT " + COLUMNS + " FROM trades"
                + " WHERE status = 'open' AND created_at < ? ORDER BY created_at", createdBefore));
    }

    /**
     * Open trades, newest first, whose text or items mention {@code query} (either may be null).
     * An item ID such as "golden_apple" also matches "golden apple" typed in the text.
     */
    public CompletableFuture<List<Trade>> search(String query, String discordId, int limit) {
        return database.submit(c -> {
            StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM trades WHERE status = 'open'");
            List<Object> params = new ArrayList<>();
            if (query != null && !query.isBlank()) {
                String q = query.trim().toLowerCase(Locale.ROOT);
                String spaced = "%" + q.replace('_', ' ') + "%";
                String underscored = "%" + q.replace(' ', '_') + "%";
                sql.append(" AND (lower(offering) LIKE ? OR lower(wanted) LIKE ? OR offer_items LIKE ? OR want_items LIKE ?)");
                params.add(spaced);
                params.add(spaced);
                params.add(underscored);
                params.add(underscored);
            }
            if (discordId != null) {
                sql.append(" AND discord_id = ?");
                params.add(discordId);
            }
            sql.append(" ORDER BY created_at DESC LIMIT ?");
            params.add(limit);
            return select(c, sql.toString(), params.toArray());
        });
    }

    /** Records (or changes) {@code raterId}'s rating of the other trader in this trade. */
    public CompletableFuture<Void> rate(String messageId, String raterId, String targetId, boolean vouch, long now) {
        return rate(messageId, raterId, targetId, "trade", vouch, now);
    }

    /** kind is "trade" (the other trader) or "middleman" (the trade's middleman). */
    public CompletableFuture<Void> rate(String messageId, String raterId, String targetId, String kind, boolean vouch, long now) {
        return database.submit(c -> {
            try (PreparedStatement insert = c.prepareStatement("INSERT OR REPLACE INTO trade_ratings"
                    + " (message_id, rater_id, target_id, kind, vouch, rated_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, messageId);
                insert.setString(2, raterId);
                insert.setString(3, targetId);
                insert.setString(4, kind);
                insert.setInt(5, vouch ? 1 : 0);
                insert.setLong(6, now);
                insert.executeUpdate();
            }
            return null;
        });
    }

    /** A rater's current ratings in one trade: kind ("trade" or "middleman") to vouch (true) or scam report. */
    public CompletableFuture<java.util.Map<String, Boolean>> ratingsBy(String messageId, String raterId) {
        return database.submit(c -> {
            java.util.Map<String, Boolean> ratings = new java.util.HashMap<>();
            try (PreparedStatement select = c.prepareStatement(
                    "SELECT kind, vouch FROM trade_ratings WHERE message_id = ? AND rater_id = ?")) {
                select.setString(1, messageId);
                select.setString(2, raterId);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        ratings.put(rs.getString("kind"), rs.getInt("vouch") == 1);
                    }
                }
            }
            return ratings;
        });
    }

    public CompletableFuture<Rep> rep(String discordId) {
        return rep(discordId, "trade");
    }

    /** A middleman's rep: ratings from the traders they middlemanned for, and those (confirmed) trades. */
    public CompletableFuture<Rep> middlemanRep(String discordId) {
        return rep(discordId, "middleman");
    }

    private CompletableFuture<Rep> rep(String discordId, String kind) {
        return database.submit(c -> {
            int vouches = 0;
            int scams = 0;
            // Only each rater's latest rating counts, so two friends can't farm rep by trading back and forth.
            try (PreparedStatement select = c.prepareStatement("""
                    SELECT vouch FROM trade_ratings r
                    WHERE target_id = ? AND kind = ? AND rater_id != target_id AND rated_at = (
                        SELECT MAX(rated_at) FROM trade_ratings
                        WHERE target_id = r.target_id AND rater_id = r.rater_id AND kind = r.kind)
                    GROUP BY rater_id""")) {
                select.setString(1, discordId);
                select.setString(2, kind);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        if (rs.getInt("vouch") == 1) {
                            vouches++;
                        } else {
                            scams++;
                        }
                    }
                }
            }
            int trades;
            try (PreparedStatement count = c.prepareStatement(kind.equals("middleman")
                    ? "SELECT COUNT(*) FROM trades WHERE status = 'traded' AND confirmed = 1 AND middleman_id = ? AND middleman_used = 1"
                    : "SELECT COUNT(*) FROM trades WHERE status = 'traded' AND confirmed = 1 AND (discord_id = ? OR partner_id = ?)")) {
                count.setString(1, discordId);
                if (!kind.equals("middleman")) {
                    count.setString(2, discordId);
                }
                try (ResultSet rs = count.executeQuery()) {
                    trades = rs.next() ? rs.getInt(1) : 0;
                }
            }
            return new Rep(vouches, scams, trades);
        });
    }

    private static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value == 1;
    }

    private static List<Trade> select(Connection c, String sql, Object... params) throws SQLException {
        List<Trade> trades = new ArrayList<>();
        try (PreparedStatement select = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                select.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    int confirmed = rs.getInt("confirmed");
                    // wasNull() describes the last column read, so it has to be asked straight away.
                    Boolean answer = rs.wasNull() ? null : confirmed == 1;
                    trades.add(new Trade(rs.getString("message_id"), rs.getString("channel_id"),
                            rs.getString("discord_id"), rs.getString("ign"), rs.getLong("created_at"),
                            rs.getString("offering"), rs.getString("wanted"), rs.getString("offer_items"),
                            rs.getString("want_items"), rs.getString("status"), rs.getString("partner_id"), answer,
                            rs.getString("middleman_by"), rs.getString("middleman_id"), rs.getString("thread_id"),
                            rs.getInt("thread_dedicated") == 1, rs.getInt("middleman_requests"),
                            rs.getString("middleman_cancelled_by"), nullableBoolean(rs, "middleman_used")));
                }
            }
        }
        return trades;
    }
}
