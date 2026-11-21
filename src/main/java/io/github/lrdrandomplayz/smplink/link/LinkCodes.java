package io.github.lrdrandomplayz.smplink.link;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * One-time codes for linking: a player runs /link in Minecraft and gets a code, then enters it with
 * /link on Discord. Codes live only in memory, expire after a few minutes and work once.
 */
public final class LinkCodes {

    /** The Minecraft account a code was made for. */
    public record Pending(UUID uuid, String name, long expiresAt) {
    }

    // No 0/O, 1/I/L: codes are typed by hand.
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    public static final int LENGTH = 6;

    private final Map<String, Pending> byCode = new ConcurrentHashMap<>();
    private final Map<UUID, String> byPlayer = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final long lifetimeMs;
    private final LongSupplier clock;

    public LinkCodes(long lifetimeMs) {
        this(lifetimeMs, System::currentTimeMillis);
    }

    LinkCodes(long lifetimeMs, LongSupplier clock) {
        this.lifetimeMs = lifetimeMs;
        this.clock = clock;
    }

    /** A fresh code for this player; any earlier code of theirs stops working. */
    public synchronized String create(UUID uuid, String name) {
        purgeExpired();
        String old = byPlayer.remove(uuid);
        if (old != null) {
            byCode.remove(old);
        }
        String code;
        do {
            StringBuilder builder = new StringBuilder(LENGTH);
            for (int i = 0; i < LENGTH; i++) {
                builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            code = builder.toString();
        } while (byCode.containsKey(code));
        byCode.put(code, new Pending(uuid, name, clock.getAsLong() + lifetimeMs));
        byPlayer.put(uuid, code);
        return code;
    }

    /** Uses up a code; empty if it's unknown or expired. Spaces, dashes and case are ignored. */
    public synchronized Optional<Pending> redeem(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String code = typed.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        Pending pending = byCode.remove(code);
        if (pending == null) {
            return Optional.empty();
        }
        byPlayer.remove(pending.uuid(), code);
        return pending.expiresAt() > clock.getAsLong() ? Optional.of(pending) : Optional.empty();
    }

    public long lifetimeMs() {
        return lifetimeMs;
    }

    private void purgeExpired() {
        long now = clock.getAsLong();
        for (Iterator<Map.Entry<String, Pending>> it = byCode.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Pending> entry = it.next();
            if (entry.getValue().expiresAt() <= now) {
                it.remove();
                byPlayer.remove(entry.getValue().uuid(), entry.getKey());
            }
        }
    }
}
