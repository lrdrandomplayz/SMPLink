package io.github.lrdrandomplayz.smplink.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import io.github.lrdrandomplayz.smplink.db.LinkRepository.ImportEntry;
import io.github.lrdrandomplayz.smplink.db.LinkRepository.Removal;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.Milestone;
import io.github.lrdrandomplayz.smplink.stats.StatsReader.Counters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryTest {

    @TempDir
    Path dir;

    private Database database;
    private LinkRepository links;
    private TrackingRepository tracking;

    @BeforeEach
    void open() throws Exception {
        database = new Database(dir.resolve("data.db"));
        database.open().get();
        Logger logger = Logger.getLogger("test");
        links = new LinkRepository(database, logger);
        tracking = new TrackingRepository(database);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private void link(int count) throws Exception {
        List<ImportEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(new ImportEntry("10000000000000000" + i, UUID.nameUUIDFromBytes(("p" + i).getBytes()), "Player" + i, i == 0));
        }
        links.importEntries(entries, true).get();
    }

    @Test
    void removesOnlyPlayersMissingByIdAndName() throws Exception {
        link(10);
        // Player1 is gone from the sheet; Player2's Discord ID failed to resolve but their name is still listed.
        Set<String> ids = Set.of("100000000000000000", "100000000000000003", "100000000000000004", "100000000000000005",
                "100000000000000006", "100000000000000007", "100000000000000008", "100000000000000009");
        Set<String> names = Set.of("player0", "player2", "player3");

        Removal removal = links.removeMissing(ids, names, 0.5).get();

        assertNull(removal.skippedReason());
        assertEquals(1, removal.removed().size());
        assertEquals("Player1", removal.removed().get(0).name());
        assertEquals(9, links.findAll().get().size());
    }

    @Test
    void refusesToRemoveMoreThanHalf() throws Exception {
        link(10);
        Removal removal = links.removeMissing(Set.of("100000000000000000"), Set.of(), 0.5).get();

        assertNotNull(removal.skippedReason());
        assertTrue(removal.removed().isEmpty());
        assertEquals(10, links.findAll().get().size());
    }

    @Test
    void keepsTheOriginalMemberFlag() throws Exception {
        link(2);
        assertTrue(links.findByName("Player0").get().orElseThrow().original());
        assertTrue(!links.findByName("Player1").get().orElseThrow().original());
    }

    @Test
    void recordsWhitelistMilestonesAndSnapshots() throws Exception {
        tracking.recordWhitelisted(Map.of("steve", "Steve", "alex", "Alex"), List.of()).get();
        tracking.recordWhitelisted(Map.of(), List.of("alex")).get();
        assertEquals(Map.of("steve", "Steve"), tracking.whitelisted().get());

        UUID uuid = UUID.randomUUID();
        tracking.recordMilestones(List.of(new Milestone(uuid, 24, 1000)), false, List.of(uuid)).get();
        tracking.recordMilestones(List.of(new Milestone(uuid, 50, 5000)), true, List.of()).get();
        assertEquals(Set.of(uuid), tracking.milestoneSeen().get());
        assertEquals(Set.of(24, 50), tracking.milestonesReached().get().get(uuid));
        // Only announced milestones show in the recap.
        assertEquals(1, tracking.milestonesSince(0).get().size());

        assertNull(tracking.snapshotBefore(System.currentTimeMillis()).get());
        long now = System.currentTimeMillis();
        tracking.saveSnapshot(now - 1000, Map.of(uuid, new Counters(100, 1, 2, 3, 4, 5))).get();
        tracking.saveSnapshot(now, Map.of(uuid, new Counters(200, 2, 4, 6, 8, 10))).get();
        assertEquals(now - 1000, tracking.snapshotBefore(now - 1).get().takenAt());
        assertEquals(8, tracking.snapshotBefore(now).get().counters().get(uuid).blocksMined());
    }

    @Test
    void warningsStayPendingUntilResolved() throws Exception {
        UUID uuid = UUID.randomUUID();
        tracking.addWarning("100000000000000001", uuid, "Newbie", 1_000, 8_000, 20_000).get();
        tracking.addWarning("100000000000000002", UUID.randomUUID(), "Other", 1_000, 8_000, 10_000).get();

        List<TrackingRepository.Warning> pending = tracking.pendingWarnings().get();
        assertEquals(2, pending.size());
        // Soonest deadline first.
        assertEquals("Other", pending.get(0).ign());

        tracking.resolveWarning(pending.get(0).id(), "passed").get();
        assertEquals(List.of("Newbie"), tracking.pendingWarnings().get().stream().map(TrackingRepository.Warning::ign).toList());
    }

    @Test
    void playtimeStopsCountingAtTheDeadline() throws Exception {
        SessionRepository sessions = new SessionRepository(database);
        UUID uuid = UUID.randomUUID();
        long id = sessions.open(uuid, 1_000).get();
        sessions.extend(List.of(id), 9_000).get();

        // Window 2s-6s clips the 1s-9s session to 4 seconds of play.
        assertEquals(4_000L, sessions.playedSince(2_000, 6_000).get().get(uuid));
        assertEquals(8_000L, sessions.playedSince(0, 20_000).get().get(uuid));
    }

    @Test
    void backsUpToASeparateFile() throws Exception {
        link(3);
        Path copy = dir.resolve("backup.db");
        database.backupTo(copy).get();
        assertTrue(Files.size(copy) > 0);

        Database restored = new Database(copy);
        restored.open().get();
        try {
            assertEquals(3, new LinkRepository(restored, Logger.getLogger("test")).findAll().get().size());
        } finally {
            restored.close();
        }
    }

    @Test
    void expiresOnlyOpenTradesPastTheCutoff() throws Exception {
        TradeRepository trades = new TradeRepository(database);
        trades.add(TradeRepository.Trade.open("1", "9", "100", "Old", 1_000, "text", "text", null, null)).get();
        trades.add(TradeRepository.Trade.open("2", "9", "100", "Traded", 1_000, "text", "text", null, null)).get();
        trades.add(TradeRepository.Trade.open("3", "9", "100", "Fresh", 5_000, "text", "text", null, null)).get();

        assertTrue(trades.close("2", "traded").get());
        assertEquals(false, trades.close("2", "removed").get(), "already closed");

        List<TradeRepository.Trade> old = trades.openOlderThan(2_000).get();
        assertEquals(1, old.size());
        assertEquals("Old", old.get(0).ign());
    }

    @Test
    void searchMatchesItemsAndText() throws Exception {
        TradeRepository trades = new TradeRepository(database);
        trades.add(TradeRepository.Trade.open("1", "9", "100", "A", 1_000, "64 × Diamond", "1 × Elytra", "diamond:64", "elytra:1")).get();
        trades.add(TradeRepository.Trade.open("2", "9", "200", "B", 2_000, "some golden apples", "iron", null, null)).get();
        trades.add(TradeRepository.Trade.open("3", "9", "200", "C", 3_000, "Diamond sword", "offers", "diamond_sword:1", null)).get();
        trades.close("3", "removed").get();

        assertEquals(List.of("1"), trades.search("elytra", null, 10).get().stream().map(TradeRepository.Trade::messageId).toList());
        // An item ID also finds the same words typed in the text.
        assertEquals(List.of("2"), trades.search("golden_apple", null, 10).get().stream().map(TradeRepository.Trade::messageId).toList());
        assertEquals(List.of("2", "1"), trades.search(null, null, 10).get().stream().map(TradeRepository.Trade::messageId).toList());
        assertEquals(List.of("2"), trades.search(null, "200", 10).get().stream().map(TradeRepository.Trade::messageId).toList());
    }

    @Test
    void repCountsEachRatersLatestRatingOnce() throws Exception {
        TradeRepository trades = new TradeRepository(database);
        for (String id : List.of("1", "2", "3")) {
            trades.add(TradeRepository.Trade.open(id, "9", "100", "Seller", 1_000, "x", "y", null, null)).get();
        }
        assertTrue(trades.complete("1", "200").get());
        assertTrue(trades.complete("2", "200").get());
        assertTrue(trades.complete("3", "300").get());
        assertEquals(false, trades.complete("3", "400").get(), "already traded");
        // Each partner confirmed the trade happened; only confirmed trades count.
        for (String id : List.of("1", "2", "3")) {
            assertTrue(trades.confirm(id, true).get());
        }

        // 200 vouches twice (counts once), 300 reports a scam, then 200 changes their mind on trade 2.
        trades.rate("1", "200", "100", true, 10).get();
        trades.rate("2", "200", "100", true, 20).get();
        trades.rate("3", "300", "100", false, 30).get();
        assertEquals(new TradeRepository.Rep(1, 1, 3), trades.rep("100").get());
        assertEquals(0, trades.rep("100").get().score());

        trades.rate("2", "200", "100", false, 40).get();
        assertEquals(new TradeRepository.Rep(0, 2, 3), trades.rep("100").get());
        assertEquals(new TradeRepository.Rep(0, 0, 2), trades.rep("200").get());
    }

    @Test
    void strikesCountOncePerWarningAndExpire() throws Exception {
        UUID uuid = UUID.randomUUID();
        long first = tracking.addStrike("100", uuid, "Slacker", "activity", "Missed", "the activity check", 1L, 1_000).get();
        // The same warning again returns the same strike instead of adding one.
        assertEquals(first, tracking.addStrike("100", uuid, "Slacker", "activity", "Missed", "the activity check", 1L, 1_000).get());
        tracking.addStrike("100", uuid, "Slacker", "activity", "Missed", "the activity check", 2L, 5_000).get();
        tracking.addStrike("200", uuid, "Other", "activity", "Missed", "the activity check", 3L, 5_000).get();

        assertEquals(2, tracking.strikesSince("100", 0).get().size());
        // Only strikes given after the cutoff still count.
        assertEquals(List.of(5_000L), tracking.strikesSince("100", 1_000).get().stream()
                .map(TrackingRepository.Strike::givenAt).toList());
    }

    @Test
    void manualAndRaidStrikesHaveIdsAndCanBeRemoved() throws Exception {
        UUID uuid = UUID.randomUUID();
        long raid = tracking.addStrike("100", uuid, "Raider", "raid", "Offline raiding Steve's base at 10, 20", "admin", null, 1_000).get();
        long manual = tracking.addStrike("100", uuid, "Raider", "manual", "Spawn griefing", "admin", null, 2_000).get();
        assertTrue(manual > raid);

        TrackingRepository.Strike found = tracking.findStrike(raid).get().orElseThrow();
        assertEquals("raid", found.kind());
        assertEquals("Offline raiding Steve's base at 10, 20", found.reason());
        assertNull(found.removedAt());

        assertTrue(tracking.removeStrike(raid, "admin", 3_000).get());
        assertEquals(false, tracking.removeStrike(raid, "admin", 4_000).get(), "already removed");
        assertEquals(false, tracking.removeStrike(999, "admin", 4_000).get(), "no such strike");
        assertEquals(3_000L, tracking.findStrike(raid).get().orElseThrow().removedAt());
        // A removed strike no longer counts.
        assertEquals(List.of(manual), tracking.strikesSince("100", 0).get().stream().map(TrackingRepository.Strike::id).toList());
    }

    @Test
    void permanentRemovalsMatchByDiscordOrName() throws Exception {
        tracking.recordRemoval("100", UUID.randomUUID(), "Slacker", 9_000).get();

        assertTrue(tracking.findRemoval("100", null).get().isPresent());
        assertTrue(tracking.findRemoval("999", "sLaCkEr").get().isPresent());
        assertTrue(tracking.findRemoval("999", "Someone").get().isEmpty());
        assertTrue(tracking.findRemoval(null, null).get().isEmpty());
    }

    @Test
    void strikesFromBeforeIdsAndReasonsAreKept() throws Exception {
        // A 1.13-1.16 database: activity strikes only, with a required warning_id.
        Path old = dir.resolve("old.db");
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + old); var st = c.createStatement()) {
            st.execute("CREATE TABLE strikes (id INTEGER PRIMARY KEY AUTOINCREMENT, discord_id TEXT NOT NULL, uuid TEXT NOT NULL,"
                    + " ign TEXT NOT NULL, warning_id INTEGER NOT NULL UNIQUE, given_at INTEGER NOT NULL)");
            st.execute("INSERT INTO strikes (discord_id, uuid, ign, warning_id, given_at) VALUES ('100', '"
                    + UUID.randomUUID() + "', 'Slacker', 7, 5000)");
        }
        Database upgraded = new Database(old);
        upgraded.open().get();
        try {
            TrackingRepository repo = new TrackingRepository(upgraded);
            List<TrackingRepository.Strike> strikes = repo.strikesSince("100", 0).get();
            assertEquals(1, strikes.size());
            assertEquals("activity", strikes.get(0).kind());
            assertEquals(5000L, strikes.get(0).givenAt());
            // Its warning still can't be struck twice, and new kinds of strike can be added alongside.
            assertEquals(strikes.get(0).id(), repo.addStrike("100", UUID.randomUUID(), "Slacker", "activity", "x", "y", 7L, 6000).get());
            repo.addStrike("100", UUID.randomUUID(), "Slacker", "manual", "Griefing spawn", "admin", null, 7000).get();
            assertEquals(2, repo.strikesSince("100", 0).get().size());
        } finally {
            upgraded.close();
        }
    }

    @Test
    void interestIsRecordedOncePerMember() throws Exception {
        TradeRepository trades = new TradeRepository(database);
        trades.add(TradeRepository.Trade.open("1", "9", "100", "Seller", 1_000, "x", "y", null, null)).get();
        assertTrue(trades.addInterest("1", "200", "Buyer", 2_000).get());
        assertEquals(false, trades.addInterest("1", "200", "Buyer", 3_000).get(), "already interested");
        assertTrue(trades.addInterest("1", "300", "Other", 2_500).get());
        assertEquals(List.of("Buyer", "Other"), trades.interested("1").get().stream().map(TradeRepository.Interest::ign).toList());
    }

    @Test
    void onlyConfirmedTradesCountAndTheyCanOnlyBeAnsweredOnce() throws Exception {
        TradeRepository trades = new TradeRepository(database);
        for (String id : List.of("1", "2", "3")) {
            trades.add(TradeRepository.Trade.open(id, "9", "100", "Seller", 1_000, "x", "y", null, null)).get();
            trades.complete(id, "200").get();
        }
        assertNull(trades.find("1").get().orElseThrow().confirmed(), "waiting for the partner");
        assertTrue(trades.confirm("1", true).get());
        assertEquals(false, trades.confirm("1", false).get(), "already answered");
        assertTrue(trades.confirm("2", false).get());
        // Trade 3 is never answered.
        assertEquals(Boolean.TRUE, trades.find("1").get().orElseThrow().confirmed());
        assertEquals(Boolean.FALSE, trades.find("2").get().orElseThrow().confirmed());
        assertEquals(1, trades.rep("100").get().trades());
        assertEquals(1, trades.rep("200").get().trades());
    }

    @Test
    void aMiddlemanCanOnlyClaimARequestedTradeOnceAndHasTheirOwnRep() throws Exception {
        TradeRepository trades = new TradeRepository(database);
        trades.add(TradeRepository.Trade.open("1", "9", "100", "Seller", 1_000, "x", "y", null, null)).get();
        assertEquals(false, trades.claimMiddleman("1", "900").get(), "nobody asked for a middleman yet");
        assertTrue(trades.requestMiddleman("1", "200").get());
        assertTrue(trades.claimMiddleman("1", "900").get());
        assertEquals(false, trades.claimMiddleman("1", "901").get(), "already taken");
        trades.setThread("1", "555", true).get();
        TradeRepository.Trade trade = trades.find("1").get().orElseThrow();
        assertEquals("900", trade.middlemanId());
        assertEquals("555", trade.threadId());

        trades.setMiddlemanUsed("1", true).get();
        trades.complete("1", "200").get();
        trades.confirm("1", true).get();
        // Both traders rate each other and the middleman; middleman rep is kept apart from trade rep.
        trades.rate("1", "100", "200", "trade", true, 10).get();
        trades.rate("1", "100", "900", "middleman", true, 11).get();
        trades.rate("1", "200", "900", "middleman", false, 12).get();
        assertEquals(new TradeRepository.Rep(1, 1, 1), trades.middlemanRep("900").get());
        assertEquals(new TradeRepository.Rep(0, 0, 0), trades.rep("900").get());
        assertEquals(new TradeRepository.Rep(1, 0, 1), trades.rep("200").get());
        assertEquals(java.util.Map.of("trade", true, "middleman", true), trades.ratingsBy("1", "100").get());
    }

    @Test
    void ratingsFromBeforeMiddlemenAreKept() throws Exception {
        // A 1.12-1.19 database: one rating per trade and rater, with no kind.
        Path old = dir.resolve("old-ratings.db");
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + old); var st = c.createStatement()) {
            st.execute("CREATE TABLE trade_ratings (message_id TEXT NOT NULL, rater_id TEXT NOT NULL, target_id TEXT NOT NULL,"
                    + " vouch INTEGER NOT NULL, rated_at INTEGER NOT NULL, PRIMARY KEY (message_id, rater_id))");
            st.execute("INSERT INTO trade_ratings VALUES ('1', '200', '100', 1, 5000)");
        }
        Database upgraded = new Database(old);
        upgraded.open().get();
        try {
            TradeRepository repo = new TradeRepository(upgraded);
            assertEquals(1, repo.rep("100").get().vouches());
            assertEquals(java.util.Map.of("trade", true), repo.ratingsBy("1", "200").get());
        } finally {
            upgraded.close();
        }
    }

    @Test
    void middlemenCanBeCancelledAndRequestedAtMostTwice() throws Exception {
        TradeRepository trades = new TradeRepository(database);
        trades.add(TradeRepository.Trade.open("1", "9", "100", "Seller", 1_000, "x", "y", null, null)).get();
        assertEquals(false, trades.cancelMiddleman("1", "100").get(), "nothing to cancel");
        assertTrue(trades.requestMiddleman("1", "200").get());
        assertTrue(trades.claimMiddleman("1", "900").get());
        trades.setThread("1", "555", false).get();
        assertTrue(trades.cancelMiddleman("1", "200").get());

        TradeRepository.Trade trade = trades.find("1").get().orElseThrow();
        assertNull(trade.middlemanBy());
        assertNull(trade.middlemanId());
        assertNull(trade.threadId());
        assertEquals("200", trade.middlemanCancelledBy());
        assertEquals(1, trade.middlemanRequests());

        assertTrue(trades.requestMiddleman("1", "200").get(), "a second request is allowed");
        assertTrue(trades.cancelMiddleman("1", "100").get());
        assertEquals(false, trades.requestMiddleman("1", "200").get(), "but not a third");
    }

    @Test
    void aMiddlemanTheTradersDidntUseGetsNoTradeOnTheirRecord() throws Exception {
        TradeRepository trades = new TradeRepository(database);
        trades.add(TradeRepository.Trade.open("1", "9", "100", "Seller", 1_000, "x", "y", null, null)).get();
        trades.requestMiddleman("1", "200").get();
        trades.claimMiddleman("1", "900").get();
        trades.setMiddlemanUsed("1", false).get();
        trades.complete("1", "200").get();
        trades.confirm("1", true).get();
        assertEquals(Boolean.FALSE, trades.find("1").get().orElseThrow().middlemanUsed());
        assertEquals(0, trades.middlemanRep("900").get().trades());
    }

    @Test
    void negotiationThreadsAreRememberedPerBuyer() throws Exception {
        TradeRepository trades = new TradeRepository(database);
        trades.add(TradeRepository.Trade.open("1", "9", "100", "Seller", 1_000, "x", "y", null, null)).get();
        trades.addInterest("1", "200", "Buyer", 2_000).get();
        trades.setInterestThread("1", "200", "777").get();
        assertEquals("777", trades.interested("1").get().get(0).threadId());
    }
}
