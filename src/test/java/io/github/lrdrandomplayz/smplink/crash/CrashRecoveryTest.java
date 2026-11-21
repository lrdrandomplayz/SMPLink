package io.github.lrdrandomplayz.smplink.crash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import io.github.lrdrandomplayz.smplink.crash.CrashCase.Pos;
import io.github.lrdrandomplayz.smplink.crash.CrashRecovery.ItemCount;
import io.github.lrdrandomplayz.smplink.crash.CrashRecovery.SaveGuess;
import io.github.lrdrandomplayz.smplink.crash.CrashSnapshotRepository.Snap;
import org.junit.jupiter.api.Test;

class CrashRecoveryTest {

    private static Snap snap(long t, double x, double z) {
        return new Snap(t, "world", x, 64, z);
    }

    @Test
    void saveTimeIsTheSnapshotNearestTheJoinPosition() {
        List<Snap> snaps = List.of(snap(1000, 0, 0), snap(2000, 50, 0), snap(3000, 100, 0), snap(4000, 150, 0));
        SaveGuess guess = CrashRecovery.inferSaveTime(snaps, "world", 98, 64, 2);
        assertEquals(3000, guess.time());
        assertFalse(guess.uncertain());
    }

    @Test
    void tiesTakeTheLatestSnapshot() {
        // Stood still from 1000 to 3000, then walked off.
        List<Snap> snaps = List.of(snap(1000, 10, 10), snap(2000, 10, 10), snap(3000, 10, 10), snap(4000, 60, 10));
        assertEquals(3000, CrashRecovery.inferSaveTime(snaps, "world", 10, 64, 10).time());
    }

    @Test
    void nothingNearbyGivesTheOldestAndUncertain() {
        List<Snap> snaps = List.of(snap(2000, 0, 0), snap(1000, 10, 0), snap(3000, 20, 0));
        SaveGuess guess = CrashRecovery.inferSaveTime(snaps, "world", 500, 64, 500);
        assertEquals(1000, guess.time());
        assertTrue(guess.uncertain());
    }

    @Test
    void otherWorldsDoNotMatch() {
        List<Snap> snaps = List.of(new Snap(1000, "world_nether", 0, 64, 0), new Snap(2000, "world_nether", 1, 64, 0));
        SaveGuess guess = CrashRecovery.inferSaveTime(snaps, "world", 0, 64, 0);
        assertTrue(guess.uncertain());
        assertEquals(1000, guess.time());
    }

    @Test
    void emptyHistoryIsUncertain() {
        assertTrue(CrashRecovery.inferSaveTime(List.of(), "world", 0, 0, 0).uncertain());
    }

    private static ItemCount item(String key, int amount) {
        return new ItemCount(key, key, amount, null);
    }

    @Test
    void missingIsTheSnapshotMinusWhatTheyHaveNow() {
        List<ItemCount> before = List.of(item("diamond", 5), item("dirt", 64), item("sword", 1), item("apple", 3));
        List<ItemCount> now = List.of(item("diamond", 3), item("dirt", 64), item("stone", 20), item("apple", 9));
        List<ItemCount> missing = CrashRecovery.diff(before, now);
        assertEquals(2, missing.size());
        assertEquals("diamond", missing.get(0).key());
        assertEquals(2, missing.get(0).amount());
        assertEquals("sword", missing.get(1).key());
        assertEquals(1, missing.get(1).amount());
    }

    @Test
    void duplicateKeysAreSummedOnBothSides() {
        List<ItemCount> before = List.of(item("gold", 10), item("gold", 20));
        List<ItemCount> now = List.of(item("gold", 12), item("gold", 8));
        List<ItemCount> missing = CrashRecovery.diff(before, now);
        assertEquals(1, missing.size());
        assertEquals(10, missing.get(0).amount());
    }

    @Test
    void nothingMissingIsEmpty() {
        assertTrue(CrashRecovery.diff(List.of(item("a", 1)), List.of(item("a", 1), item("b", 2))).isEmpty());
    }

    @Test
    void positionDistance() {
        assertEquals(5, new Pos("w", 0, 0, 0).distance(new Pos("w", 3, 0, 4)), 1e-9);
        assertTrue(new Pos("w", 0, 0, 0).distance(new Pos("x", 0, 0, 0)) > 1e8);
    }
}
