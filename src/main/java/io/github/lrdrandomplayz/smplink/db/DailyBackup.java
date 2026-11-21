package io.github.lrdrandomplayz.smplink.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import io.github.lrdrandomplayz.smplink.http.SyncBridge;

/**
 * Saves one copy of data.db per day into backups/ and keeps the newest {@code keep}. It checks hourly
 * whether today's copy exists, so a restart never skips a day or makes a second copy.
 */
public final class DailyBackup {

    private final Database database;
    private final Path folder;
    private final int keep;
    private final Logger logger;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SMPLink-Backup");
        thread.setDaemon(true);
        return thread;
    });

    public DailyBackup(Database database, Path folder, int keep, Logger logger) {
        this.database = database;
        this.folder = folder;
        this.keep = keep;
        this.logger = logger;
    }

    public void start() {
        // The first check waits a minute so it doesn't compete with startup work.
        timer.scheduleWithFixedDelay(this::backupIfDue, 1, 60, TimeUnit.MINUTES);
    }

    public void stop() {
        timer.shutdownNow();
    }

    private void backupIfDue() {
        try {
            Files.createDirectories(folder);
            Path today = folder.resolve("data-" + LocalDate.now() + ".db");
            if (Files.exists(today)) {
                return;
            }
            SyncBridge.await(database.backupTo(today), 60_000);
            logger.info("Backed up the database to " + today);
            prune();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Database backup failed", e);
        }
    }

    private void prune() throws IOException {
        List<Path> backups;
        try (Stream<Path> files = Files.list(folder)) {
            backups = files.filter(file -> file.getFileName().toString().matches("data-\\d{4}-\\d{2}-\\d{2}\\.db"))
                    .sorted(Comparator.comparing((Path file) -> file.getFileName().toString()).reversed())
                    .toList();
        }
        for (Path old : backups.subList(Math.min(keep, backups.size()), backups.size())) {
            Files.deleteIfExists(old);
            logger.info("Deleted old backup " + old.getFileName());
        }
    }
}
