package io.github.lrdrandomplayz.smplink.discord;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs a task at the same weekday and time every week. The next run is worked out afresh after each one
 * so clock changes can't drift it. A run missed because the server was off is not caught up later.
 */
final class WeeklySchedule {

    private WeeklySchedule() {
    }

    static void schedule(ScheduledExecutorService timer, String name, DayOfWeek day, LocalTime time, ZoneId zone,
                         Runnable task, Logger logger) {
        ZonedDateTime next = next(ZonedDateTime.now(zone), day, time);
        long delayMs = Duration.between(ZonedDateTime.now(zone), next).toMillis();
        logger.info(name + " is scheduled for " + next.toLocalDate() + " " + time + " " + zone.getId());
        timer.schedule(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, name + " failed", e);
            }
            schedule(timer, name, day, time, zone, task, logger);
        }, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }

    /** The first time strictly after {@code now} that falls on {@code day} at {@code time}. */
    static ZonedDateTime next(ZonedDateTime now, DayOfWeek day, LocalTime time) {
        ZonedDateTime candidate = now.with(TemporalAdjusters.nextOrSame(day)).with(time).withSecond(0).withNano(0);
        return candidate.isAfter(now) ? candidate : candidate.plusWeeks(1);
    }
}
