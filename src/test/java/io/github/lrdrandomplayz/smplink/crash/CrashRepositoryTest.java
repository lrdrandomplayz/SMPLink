package io.github.lrdrandomplayz.smplink.crash;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import io.github.lrdrandomplayz.smplink.crash.CrashCase.MissingItem;
import io.github.lrdrandomplayz.smplink.crash.CrashCase.Pos;
import io.github.lrdrandomplayz.smplink.crash.CrashSnapshotRepository.Snap;
import io.github.lrdrandomplayz.smplink.crash.CrashSnapshotRepository.Snapshot;
import io.github.lrdrandomplayz.smplink.db.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrashRepositoryTest {

    @TempDir
    Path dir;

    private Database database;
    private CrashSnapshotRepository snapshots;
    private CrashCaseRepository cases;

    @BeforeEach
    void open() throws Exception {
        database = new Database(dir.resolve("data.db"));
        database.open().get();
        snapshots = new CrashSnapshotRepository(database);
        snapshots.init().get();
        cases = new CrashCaseRepository(database);
        cases.init().get();
    }

    @AfterEach
    void close() {
        database.close();
    }

    private static Snapshot snap(UUID id, long t, double x) {
        return new Snapshot(id, "Steve", t, "world", x, 64, 0, 7, 0.5f, new byte[] {1, 2, (byte) t}, new byte[] {9});
    }

    @Test
    void insertPrunesToTheNewestPerPlayer() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        for (int i = 1; i <= 5; i++) {
            snapshots.insertAll(List.of(snap(a, i * 1000L, i), snap(b, i * 1000L, i)), 3).get();
        }
        assertEquals(3, snapshots.count(a).get());
        assertEquals(3, snapshots.count(b).get());
        List<Snap> kept = snapshots.positions(a, 0, 10_000).get();
        assertEquals(List.of(3000L, 4000L, 5000L), kept.stream().map(Snap::takenAt).toList());
        assertEquals(5000, snapshots.newestTakenAt().get());
    }

    @Test
    void latestBeforeAndWindows() throws Exception {
        UUID a = UUID.randomUUID();
        snapshots.insert(snap(a, 1000, 1), 20).get();
        snapshots.insert(snap(a, 2000, 2), 20).get();
        snapshots.insert(snap(a, 3000, 3), 20).get();
        Snapshot s = snapshots.latestBefore(a, 2500).get().orElseThrow();
        assertEquals(2000, s.takenAt());
        assertEquals(2.0, s.x());
        assertEquals(7, s.level());
        assertArrayEquals(new byte[] {1, 2, (byte) 2000}, s.inventory());
        assertTrue(snapshots.latestBefore(a, 500).get().isEmpty());
        assertEquals(2, snapshots.positions(a, 1500, 3500).get().size());
        assertEquals(1, snapshots.playersBetween(2900, 3000).get().size());
        assertEquals(0, snapshots.playersBetween(3100, 4000).get().size());
        assertEquals(2, snapshots.deleteOlderThan(3000).get());
        assertEquals(1, snapshots.count(a).get());
    }

    @Test
    void newestSessionEndReadsTheSessionsTable() throws Exception {
        assertEquals(0, snapshots.newestSessionEnd().get());
        database.submit(c -> {
            c.createStatement().execute("INSERT INTO sessions (uuid, started_at, ended_at) VALUES ('x', 1, 777)");
            return null;
        }).get();
        assertEquals(777, snapshots.newestSessionEnd().get());
    }

    @Test
    void casesRoundTrip() throws Exception {
        UUID id = UUID.randomUUID();
        long caseId = cases.addAwaiting(id, "Steve", 5000).get();
        CrashCase waiting = cases.awaiting(id).get().orElseThrow();
        assertEquals(caseId, waiting.id());
        assertEquals("awaiting", waiting.status());

        CrashCase open = new CrashCase(caseId, id, "Steve", 1000, true, 5000,
                List.of(new MissingItem("Diamond", 2, "QUJD"), new MissingItem("Oak Log", 64, "REVG")),
                new Pos("world", 1.5, 64, -3), new Pos("world", 10, 64, 10), 12.5,
                List.of("Took 3x DIAMOND"), "open", false, false, true, "queued");
        cases.save(open).get();
        assertTrue(cases.awaiting(id).get().isEmpty());
        CrashCase loaded = cases.get(caseId).get().orElseThrow();
        assertEquals(open, loaded);
        assertEquals(1, cases.givePending(id).get().size());
        assertEquals(1, cases.open().get().size());
        cases.save(loaded.with("done", true, true, false, "ok")).get();
        assertTrue(cases.givePending(id).get().isEmpty());
        assertEquals(1, cases.deleteOlderThan(6000).get());
    }
}
