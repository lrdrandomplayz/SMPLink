package io.github.lrdrandomplayz.smplink.crash;

import java.util.concurrent.CompletableFuture;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository;

/**
 * kv "shutdown.clean": "0" while the server runs, "1" after an orderly shutdown. If the plugin starts and finds
 * "0", the last run was killed. Call {@link #begin()} early in onEnable (before the snapshotter starts), and await
 * {@link #markClean()} in onDisable before the database closes.
 */
public final class ShutdownMarker {

    public static final String KEY = "shutdown.clean";

    /** {@code crashedAt}: best estimate (epoch ms) of when the server died. */
    public record CrashInfo(long crashedAt) {
    }

    private final TrackingRepository tracking;
    private final CrashSnapshotRepository snapshots;

    public ShutdownMarker(TrackingRepository tracking, CrashSnapshotRepository snapshots) {
        this.tracking = tracking;
        this.snapshots = snapshots;
    }

    /** Null when the last shutdown was clean (or this is the first run); then marks the server as running. */
    public CompletableFuture<CrashInfo> begin() {
        return tracking.value(KEY).thenCompose(old -> {
            CompletableFuture<CrashInfo> info = "0".equals(old)
                    ? snapshots.newestTakenAt().thenCombine(snapshots.newestSessionEnd(),
                            (a, b) -> new CrashInfo(Math.max(a, b)))
                    : CompletableFuture.completedFuture(null);
            return info.thenCompose(i -> tracking.setValue(KEY, "0").thenApply(v -> i));
        });
    }

    public CompletableFuture<Void> markClean() {
        return tracking.setValue(KEY, "1");
    }
}
