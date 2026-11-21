package io.github.lrdrandomplayz.smplink.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Mojang name lookups with a JSON cache and a 100-per-minute throttle. Shared by the sync and /whitelist add. */
public final class MojangResolver {

    public sealed interface Lookup permits Found, Missing, Failed {
    }

    public record Found(UUID uuid, String name) implements Lookup {
    }

    public record Missing() implements Lookup {
    }

    public record Failed(String message) implements Lookup {
    }

    public static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private static final String LOOKUP_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final int LOOKUPS_PER_WINDOW = 100;
    private static final long WINDOW_MS = 60_000;
    // A name that is free today may be registered later, so misses are only trusted for a day.
    private static final long MISS_TTL_MS = 24 * 60 * 60 * 1000L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final class Cache {
        Map<String, CachedProfile> found = new HashMap<>();
        Map<String, Long> missing = new HashMap<>();
    }

    private record CachedProfile(String uuid, String name, long resolvedAt) {
    }

    private final HttpClient http;
    private final Path cacheFile;
    private final Logger logger;
    private final ArrayDeque<Long> requestTimes = new ArrayDeque<>();
    private Cache cache = new Cache();
    private boolean dirty;

    public MojangResolver(HttpClient http, Path cacheFile, Logger logger) {
        this.http = http;
        this.cacheFile = cacheFile;
        this.logger = logger;
        load();
    }

    /** The UUID an offline-mode server gives a player: a type 3 UUID of "OfflinePlayer:<name>", case sensitive. */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** Errors are never cached, so a Mojang outage cannot mark real accounts as cracked. */
    public synchronized Lookup resolve(String name) throws InterruptedException {
        String key = name.toLowerCase(Locale.ROOT);
        CachedProfile hit = cache.found.get(key);
        if (hit != null) {
            return new Found(UUID.fromString(hit.uuid()), hit.name());
        }
        Long missedAt = cache.missing.get(key);
        if (missedAt != null && System.currentTimeMillis() - missedAt < MISS_TTL_MS) {
            return new Missing();
        }

        for (int attempt = 0; attempt < 3; attempt++) {
            throttle();
            HttpResponse<String> response;
            try {
                response = http.send(HttpRequest.newBuilder(
                                URI.create(LOOKUP_URL + URLEncoder.encode(name, StandardCharsets.UTF_8)))
                        .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException e) {
                return new Failed("Mojang timed out");
            } catch (IOException e) {
                return new Failed(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }

            int status = response.statusCode();
            if (status == 200) {
                JsonObject profile = JsonParser.parseString(response.body()).getAsJsonObject();
                UUID uuid = dashed(profile.get("id").getAsString());
                String canonical = profile.get("name").getAsString();
                cache.found.put(key, new CachedProfile(uuid.toString(), canonical, System.currentTimeMillis()));
                cache.missing.remove(key);
                dirty = true;
                return new Found(uuid, canonical);
            }
            if (status == 204 || status == 404) {
                cache.missing.put(key, System.currentTimeMillis());
                dirty = true;
                return new Missing();
            }
            if (status == 429) {
                long retryAfter = response.headers().firstValueAsLong("retry-after").orElse(0);
                Thread.sleep(retryAfter > 0 ? retryAfter * 1000 : WINDOW_MS);
                continue;
            }
            return new Failed("Mojang returned HTTP " + status);
        }
        return new Failed("Mojang kept rate limiting");
    }

    public synchronized void save() {
        if (!dirty) {
            return;
        }
        try {
            Files.createDirectories(cacheFile.getParent());
            Path temp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(cache));
            Files.move(temp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (IOException e) {
            logger.warning("Could not write the Mojang cache: " + e.getMessage());
        }
    }

    private void load() {
        if (!Files.exists(cacheFile)) {
            return;
        }
        try {
            Cache loaded = GSON.fromJson(Files.readString(cacheFile), Cache.class);
            if (loaded != null) {
                cache = loaded;
                if (cache.found == null) {
                    cache.found = new HashMap<>();
                }
                if (cache.missing == null) {
                    cache.missing = new HashMap<>();
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.warning("Ignoring unreadable Mojang cache " + cacheFile + ": " + e.getMessage());
        }
    }

    private void throttle() throws InterruptedException {
        long now = System.currentTimeMillis();
        while (!requestTimes.isEmpty() && now - requestTimes.peekFirst() >= WINDOW_MS) {
            requestTimes.pollFirst();
        }
        if (requestTimes.size() >= LOOKUPS_PER_WINDOW) {
            Thread.sleep(requestTimes.peekFirst() + WINDOW_MS - now);
            requestTimes.pollFirst();
        }
        requestTimes.addLast(System.currentTimeMillis());
    }

    private static UUID dashed(String hex) {
        return UUID.fromString(hex.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
    }
}
