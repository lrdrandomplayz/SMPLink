package io.github.lrdrandomplayz.smplink.discord;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Sliding-window limiter kept in memory; a restart resets it, which is fine for abuse protection. */
final class RateLimiter {

    private final int limit;
    private final long windowMs;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    RateLimiter(int limit, long windowMs) {
        this.limit = limit;
        this.windowMs = windowMs;
    }

    /** Like {@link #take} but records nothing: 0 if a use would be allowed now. */
    long peek(String userId) {
        long now = System.currentTimeMillis();
        Deque<Long> times = hits.get(userId);
        if (times == null) {
            return 0;
        }
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() >= windowMs) {
                times.pollFirst();
            }
            return times.size() >= limit ? times.peekFirst() + windowMs - now : 0;
        }
    }

    /** Records a use and returns 0 if allowed, or the milliseconds until the next slot frees up. */
    long take(String userId) {
        long now = System.currentTimeMillis();
        Deque<Long> times = hits.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() >= windowMs) {
                times.pollFirst();
            }
            if (times.size() >= limit) {
                return times.peekFirst() + windowMs - now;
            }
            times.addLast(now);
        }
        // Drop idle users now and then so the map can't grow without bound.
        if (hits.size() > 1000) {
            hits.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty() || now - e.getValue().peekLast() >= windowMs;
                }
            });
        }
        return 0;
    }
}
