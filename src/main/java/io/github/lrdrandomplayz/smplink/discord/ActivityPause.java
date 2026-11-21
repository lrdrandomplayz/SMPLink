package io.github.lrdrandomplayz.smplink.discord;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.logging.Logger;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository;
import io.github.lrdrandomplayz.smplink.http.SyncBridge;
import io.github.lrdrandomplayz.smplink.service.PlayerService;

/**
 * Whether the activity check is paused, and since when the current season counts. While paused, nobody is
 * checked, warned or struck. Resuming (by hand, or when the pause date passes) starts a new season: playtime only
 * counts from then, so the first check waits until the season has a full week. Kept in the database (kv table).
 */
final class ActivityPause {

    /** A finished season reset: open warnings cancelled and activity strikes taken back. */
    record Reset(int warnings, int strikes) {
    }

    private static final String PAUSED = "activity.paused";
    private static final String UNTIL = "activity.paused-until";
    private static final String BY = "activity.paused-by";
    private static final String SEASON = "activity.season-start";

    private final TrackingRepository tracking;
    private final ZoneId zone;
    private final Logger logger;
    private boolean paused;
    private long until;
    private String by = "";
    private long seasonStart;

    ActivityPause(TrackingRepository tracking, ZoneId zone, Logger logger) {
        this.tracking = tracking;
        this.zone = zone;
        this.logger = logger;
        try {
            paused = "1".equals(get(PAUSED));
            until = parse(get(UNTIL));
            by = get(BY) == null ? "" : get(BY);
            seasonStart = parse(get(SEASON));
        } catch (Exception e) {
            logger.warning("[activity] Could not read the activity check's pause state: " + e.getMessage());
        }
    }

    private String get(String key) throws Exception {
        return SyncBridge.await(tracking.value(key), PlayerService.DB_TIMEOUT_MS);
    }

    private static long parse(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    long startOfDay(LocalDate date) {
        return date.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** Whether checks are paused now; a pause whose date has passed ends here, starting a new season. */
    synchronized boolean isPaused() {
        if (paused && until > 0 && System.currentTimeMillis() >= until) {
            logger.info("[activity] The pause ended; activity checks are back on, counting from " + java.time.Instant.ofEpochMilli(until));
            save(false, 0, "", until);
        }
        return paused;
    }

    /** When a timed pause ends (0 if not paused or paused until resumed by hand). */
    synchronized long until() {
        return paused ? until : 0;
    }

    synchronized String pausedBy() {
        return by;
    }

    /** When the current season started counting (0 if there has been no pause yet). */
    synchronized long seasonStart() {
        isPaused();
        return seasonStart;
    }

    /** Pauses until {@code untilMs} (0 = until resumed by hand). */
    synchronized void pause(long untilMs, String who) {
        save(true, untilMs, who, seasonStart);
    }

    /** Ends the pause now; the new season counts from now. */
    synchronized void resume(String who) {
        logger.info("[activity] " + who + " resumed activity checks");
        save(false, 0, "", System.currentTimeMillis());
    }

    /** Cancels open warnings and takes back every activity strike (raid and manual strikes stay). */
    Reset resetSeason(String who) throws Exception {
        long now = System.currentTimeMillis();
        int warnings = SyncBridge.await(tracking.cancelPendingWarnings(now), PlayerService.DB_TIMEOUT_MS);
        int strikes = SyncBridge.await(tracking.removeStrikesOfKind("activity", who, now), PlayerService.DB_TIMEOUT_MS);
        return new Reset(warnings, strikes);
    }

    private void save(boolean nowPaused, long nowUntil, String who, long nowSeason) {
        paused = nowPaused;
        until = nowUntil;
        by = who == null ? "" : who;
        seasonStart = nowSeason;
        try {
            SyncBridge.await(tracking.setValue(PAUSED, nowPaused ? "1" : "0"), PlayerService.DB_TIMEOUT_MS);
            SyncBridge.await(tracking.setValue(UNTIL, String.valueOf(nowUntil)), PlayerService.DB_TIMEOUT_MS);
            SyncBridge.await(tracking.setValue(BY, by), PlayerService.DB_TIMEOUT_MS);
            SyncBridge.await(tracking.setValue(SEASON, String.valueOf(nowSeason)), PlayerService.DB_TIMEOUT_MS);
        } catch (Exception e) {
            logger.warning("[activity] Could not save the activity check's pause state: " + e.getMessage());
        }
    }
}
