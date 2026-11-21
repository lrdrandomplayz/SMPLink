package io.github.lrdrandomplayz.smplink.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class WeeklyScheduleTest {

    private static final ZoneId GMT8 = ZoneId.of("GMT+8");
    private static final LocalTime EIGHT_PM = LocalTime.of(20, 0);

    @Test
    void laterTheSameSunday() {
        ZonedDateTime sundayMorning = ZonedDateTime.of(2026, 9, 27, 9, 0, 0, 0, GMT8);
        assertEquals(ZonedDateTime.of(2026, 9, 27, 20, 0, 0, 0, GMT8),
                WeeklySchedule.next(sundayMorning, DayOfWeek.SUNDAY, EIGHT_PM));
    }

    @Test
    void nextWeekOnceTodaysTimeHasPassed() {
        ZonedDateTime sundayNight = ZonedDateTime.of(2026, 9, 27, 20, 0, 0, 0, GMT8);
        assertEquals(ZonedDateTime.of(2026, 10, 4, 20, 0, 0, 0, GMT8),
                WeeklySchedule.next(sundayNight, DayOfWeek.SUNDAY, EIGHT_PM));
    }

    @Test
    void comingSundayFromMidweek() {
        ZonedDateTime wednesday = ZonedDateTime.of(2026, 9, 30, 12, 0, 0, 0, GMT8);
        assertEquals(ZonedDateTime.of(2026, 10, 4, 20, 0, 0, 0, GMT8),
                WeeklySchedule.next(wednesday, DayOfWeek.SUNDAY, EIGHT_PM));
    }
}
