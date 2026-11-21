package io.github.lrdrandomplayz.smplink.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class WeekResetTest {

    private static final ZoneId ZONE = ZoneId.of("GMT+8");

    private static ZonedDateTime at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, ZONE);
    }

    @Test
    void weeksStartAtTheRecap() {
        // 4 October 2026 is a Sunday.
        assertEquals(at(4, 20, 0), PlayerService.lastReset(at(4, 21, 15), DayOfWeek.SUNDAY, LocalTime.of(20, 0)), "just after the recap");
        assertEquals(at(4, 20, 0), PlayerService.lastReset(at(4, 20, 0), DayOfWeek.SUNDAY, LocalTime.of(20, 0)), "at the recap");
        assertEquals(at(27 - 23, 20, 0).minusWeeks(1), PlayerService.lastReset(at(4, 19, 59), DayOfWeek.SUNDAY, LocalTime.of(20, 0)),
                "a minute before: still last week's");
        assertEquals(at(4, 20, 0), PlayerService.lastReset(at(8, 9, 0), DayOfWeek.SUNDAY, LocalTime.of(20, 0)), "midweek");
    }

    @Test
    void monthStartsOnTheFirstAtMidnightInTheZone() {
        ZoneId zone = ZoneId.of("GMT+8");
        assertEquals(ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, zone),
                PlayerService.monthStart(ZonedDateTime.of(2026, 11, 7, 18, 30, 0, 0, zone)));
        assertEquals(ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, zone),
                PlayerService.monthStart(ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, zone)));
    }
}
