package io.github.lrdrandomplayz.smplink.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class JoinLockTest {

    private static final ZoneId KL = ZoneId.of("Asia/Kuala_Lumpur");

    @Test
    void readsTheOpeningTimeInServerTime() {
        long opens = JoinLock.parse("2026-11-07 13:00", KL);
        assertEquals("Saturday 7 November at 1:00 PM (GMT+8)", JoinLock.shown(opens, KL));
        assertThrows(IllegalArgumentException.class, () -> JoinLock.parse("7 Nov 1pm", KL));
    }

    @Test
    void countsDownInPlainWords() {
        long minute = 60_000, hour = 60 * minute, day = 24 * hour;
        assertEquals("2 days, 4 hours", JoinLock.countdown(2 * day + 4 * hour + 30 * minute));
        assertEquals("1 day", JoinLock.countdown(day + 20 * minute));
        assertEquals("3 hours, 5 minutes", JoinLock.countdown(3 * hour + 5 * minute));
        assertEquals("1 minute", JoinLock.countdown(minute + 10_000));
        assertEquals("under a minute", JoinLock.countdown(20_000));
    }
}
