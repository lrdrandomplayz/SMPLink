package io.github.lrdrandomplayz.smplink.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.logging.Logger;
import io.github.lrdrandomplayz.smplink.db.Database;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActivityPauseTest {

    private static final ZoneId ZONE = ZoneId.of("GMT+8");
    private static final Logger LOGGER = Logger.getLogger("test");

    @TempDir
    Path dir;
    private Database database;
    private TrackingRepository tracking;

    @BeforeEach
    void open() throws Exception {
        database = new Database(dir.resolve("data.db"));
        database.open().get();
        tracking = new TrackingRepository(database);
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void pausingAndResumingStartsANewSeason() throws Exception {
        ActivityPause pause = new ActivityPause(tracking, ZONE, LOGGER);
        pause.pause(0, "Alex");
        assertTrue(pause.isPaused());
        assertEquals(0, pause.until(), "paused until resumed by hand");
        assertEquals("Alex", new ActivityPause(tracking, ZONE, LOGGER).pausedBy(), "kept across restarts");

        long before = System.currentTimeMillis();
        pause.resume("Alex");
        assertFalse(pause.isPaused());
        assertTrue(pause.seasonStart() >= before, "the season counts from the resume");
        assertFalse(new ActivityPause(tracking, ZONE, LOGGER).isPaused());

        pause.pause(System.currentTimeMillis() - 1, "Alex");
        assertFalse(pause.isPaused(), "a pause whose date has passed ends by itself");
    }
}
