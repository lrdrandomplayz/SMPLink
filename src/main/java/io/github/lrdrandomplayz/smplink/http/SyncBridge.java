package io.github.lrdrandomplayz.smplink.http;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Runs Bukkit API work on the main thread for the HTTP threads, with a hard 2 second limit. */
public final class SyncBridge {

    private static final long SYNC_TIMEOUT_MS = 2_000;

    private final Plugin plugin;

    public SyncBridge(Plugin plugin) {
        this.plugin = plugin;
    }

    public <T> T call(Callable<T> task) throws Exception {
        return await(Bukkit.getScheduler().callSyncMethod(plugin, task), SYNC_TIMEOUT_MS);
    }

    /** Waits for a future and rethrows the task's own exception instead of an ExecutionException wrapper. */
    public static <T> T await(Future<T> future, long timeoutMs) throws Exception {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(false);
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }
}
