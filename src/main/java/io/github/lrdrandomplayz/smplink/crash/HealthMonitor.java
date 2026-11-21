package io.github.lrdrandomplayz.smplink.crash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Once a minute: heap, container memory (cgroup), TPS, MSPT, chunks and entities go to a daily log
 * (health-YYYY-MM-DD.log in the plugin folder, kept 2 days). Memory at the alert fraction, or TPS under the alert
 * for 3 checks running, calls {@code alert} (at most every 30 minutes) and, if relief is on, pauses Chunky and
 * lowers view distance by 2 (min 4) until 10 healthy checks in a row.
 */
public final class HealthMonitor {

    static final long PERIOD_TICKS = 60 * 20L;
    static final long COOLDOWN_MS = 30 * 60_000L;
    static final int LOW_TPS_CHECKS = 3;
    static final int HEALTHY_TO_RESTORE = 10;
    static final int MIN_VIEW = 4;
    static final int VIEW_STEP = 2;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** What the main thread read. */
    private record Sample(double tps, double mspt, int chunks, int entities, int players) {
    }

    /** Memory in bytes; max 0 means unknown. */
    record Memory(long used, long max) {

        double fraction() {
            return max > 0 ? (double) used / max : 0;
        }
    }

    private final Plugin plugin;
    private final CrashSettings settings;
    private final Consumer<String> alert;
    private final Logger logger;
    private final Path folder;
    private BukkitTask task;

    // Only touched from check(), which runs one at a time on the async scheduler.
    private int lowTps;
    private int healthy;
    private long lastAlert;
    private boolean relieved;
    private boolean chunkyPaused;
    private final Map<String, Integer> originalViews = new HashMap<>();

    public HealthMonitor(Plugin plugin, CrashSettings settings, Consumer<String> alert) {
        this.plugin = plugin;
        this.settings = settings;
        this.alert = alert;
        this.logger = plugin.getLogger();
        this.folder = plugin.getDataFolder().toPath();
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::sample, PERIOD_TICKS, PERIOD_TICKS);
    }

    /** Restores view distances if relief was active. Call from onDisable (main thread). */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (relieved) {
            restore();
        }
    }

    /** Main thread: the quick Bukkit reads, then hand over to the async scheduler for files. */
    private void sample() {
        int chunks = 0;
        int entities = 0;
        for (World world : Bukkit.getWorlds()) {
            chunks += world.getLoadedChunks().length;
            entities += world.getEntityCount();
        }
        Sample sample = new Sample(Bukkit.getTPS()[0], Bukkit.getAverageTickTime(), chunks, entities,
                Bukkit.getOnlinePlayers().size());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                check(sample);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "[health] check failed", e);
            }
        });
    }

    private synchronized void check(Sample s) {
        Runtime rt = Runtime.getRuntime();
        long heapMax = rt.maxMemory();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        Memory container = containerMemory();

        write(String.format("%s heap=%d/%dMB container=%s tps=%.2f mspt=%.1f chunks=%d entities=%d players=%d%s",
                LocalDateTime.now().format(STAMP), heapUsed >> 20, heapMax >> 20,
                container.max() > 0 ? (container.used() >> 20) + "/" + (container.max() >> 20) + "MB" : "n/a",
                s.tps(), s.mspt(), s.chunks(), s.entities(), s.players(), relieved ? " relief=on" : ""));
        deleteOldLogs();

        boolean memoryHigh = container.max() > 0 && container.fraction() >= settings.memoryAlert();
        lowTps = s.tps() < settings.tpsAlert() ? lowTps + 1 : 0;
        boolean tpsLow = lowTps >= LOW_TPS_CHECKS;
        boolean bad = memoryHigh || tpsLow;
        healthy = bad || s.tps() < settings.tpsAlert() ? 0 : healthy + 1;

        long now = System.currentTimeMillis();
        if (bad && now - lastAlert >= COOLDOWN_MS) {
            lastAlert = now;
            StringBuilder text = new StringBuilder("Server health warning: ");
            List<String> reasons = new ArrayList<>();
            if (memoryHigh) {
                reasons.add(String.format("container memory %d%% (%d/%d MB)", Math.round(container.fraction() * 100),
                        container.used() >> 20, container.max() >> 20));
            }
            if (tpsLow) {
                reasons.add(String.format("TPS %.1f for %d checks", s.tps(), lowTps));
            }
            text.append(String.join(", ", reasons)).append(String.format(". MSPT %.1f, %d chunks, %d entities, %d players, heap %d/%d MB.",
                    s.mspt(), s.chunks(), s.entities(), s.players(), heapUsed >> 20, heapMax >> 20));
            if (settings.relief()) {
                text.append(' ').append(relieve());
            }
            safeAlert(text.toString());
        } else if (bad && settings.relief() && !relieved) {
            relieve(); // still in the alert cooldown, but relief hasn't been applied yet
        }
        if (relieved && healthy >= HEALTHY_TO_RESTORE) {
            String result = restore();
            safeAlert("Server health is back to normal. " + result);
        }
    }

    private void safeAlert(String text) {
        try {
            alert.accept(text);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "[health] alert callback failed", e);
        }
    }

    /** Applies relief on the main thread and waits for what it did. */
    private String relieve() {
        if (relieved) {
            return "Relief is already active.";
        }
        return onMain(() -> {
            List<String> done = new ArrayList<>();
            if (Bukkit.getPluginManager().isPluginEnabled("Chunky")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "chunky pause");
                chunkyPaused = true;
                done.add("paused Chunky");
            }
            List<String> lowered = new ArrayList<>();
            for (World world : Bukkit.getWorlds()) {
                int view = world.getViewDistance();
                int target = Math.max(MIN_VIEW, view - VIEW_STEP);
                if (target < view) {
                    originalViews.putIfAbsent(world.getName(), view);
                    world.setViewDistance(target);
                    lowered.add(world.getName() + " " + view + "->" + target);
                }
            }
            if (!lowered.isEmpty()) {
                done.add("lowered view distance (" + String.join(", ", lowered) + ")");
            }
            relieved = true;
            return done.isEmpty() ? "Relief: nothing to reduce." : "Relief: " + String.join(" and ", done) + ".";
        }, "Relief could not be applied.");
    }

    private String restore() {
        if (Bukkit.isPrimaryThread()) {
            return doRestore();
        }
        return onMain(this::doRestore, "Relief could not be undone.");
    }

    private String doRestore() {
        List<String> done = new ArrayList<>();
        for (Map.Entry<String, Integer> original : originalViews.entrySet()) {
            World world = Bukkit.getWorld(original.getKey());
            if (world != null) {
                world.setViewDistance(original.getValue());
            }
        }
        if (!originalViews.isEmpty()) {
            done.add("restored view distance");
        }
        originalViews.clear();
        if (chunkyPaused && Bukkit.getPluginManager().isPluginEnabled("Chunky")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "chunky continue");
            done.add("resumed Chunky");
        }
        chunkyPaused = false;
        relieved = false;
        healthy = 0;
        return done.isEmpty() ? "" : "Relief ended: " + String.join(" and ", done) + ".";
    }

    private String onMain(java.util.function.Supplier<String> work, String failure) {
        if (Bukkit.isPrimaryThread()) {
            return work.get();
        }
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, work::get).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.log(Level.WARNING, "[health] " + failure, e);
            return failure;
        }
    }

    // ---------------------------------------------------------------- files

    /** cgroup v2, then v1; max 0 when not in a container (e.g. a Windows dev box). */
    static Memory containerMemory() {
        Memory v2 = readMemory(Path.of("/sys/fs/cgroup/memory.current"), Path.of("/sys/fs/cgroup/memory.max"));
        if (v2.max() > 0) {
            return v2;
        }
        return readMemory(Path.of("/sys/fs/cgroup/memory/memory.usage_in_bytes"),
                Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes"));
    }

    private static Memory readMemory(Path usedFile, Path limitFile) {
        long used = number(usedFile);
        long limit = number(limitFile);
        // "max" (v2) or a huge number (v1) means no limit.
        return used > 0 && limit > 0 && limit < (1L << 50) ? new Memory(used, limit) : new Memory(used, 0);
    }

    private static long number(Path file) {
        try {
            if (!Files.isReadable(file)) {
                return 0;
            }
            return Long.parseLong(Files.readString(file).trim());
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    private void write(String line) {
        Path file = folder.resolve("health-" + LocalDate.now().format(DAY) + ".log");
        try {
            Files.createDirectories(folder);
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.warning("[health] couldn't write " + file.getFileName() + ": " + e.getMessage());
        }
    }

    private void deleteOldLogs() {
        LocalDate oldest = LocalDate.now().minusDays(2);
        try (Stream<Path> files = Files.list(folder)) {
            files.filter(f -> f.getFileName().toString().matches("health-\\d{4}-\\d{2}-\\d{2}\\.log")).forEach(f -> {
                String name = f.getFileName().toString();
                try {
                    if (LocalDate.parse(name.substring(7, 17)).isBefore(oldest)) {
                        Files.deleteIfExists(f);
                    }
                } catch (RuntimeException | IOException e) {
                    // leave it for next time
                }
            });
        } catch (IOException e) {
            // folder unreadable; the write above will have complained
        }
    }
}
