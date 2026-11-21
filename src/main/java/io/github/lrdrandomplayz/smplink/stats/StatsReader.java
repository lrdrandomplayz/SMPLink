package io.github.lrdrandomplayz.smplink.stats;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

public final class StatsReader {

    public record Counters(long playTicks, long deaths, long mobKills, long playerKills,
                           long blocksMined, long walkedCm) {
        static final Counters EMPTY = new Counters(0, 0, 0, 0, 0, 0);
    }

    private final Path statsFolder;
    // Parsed stats files, reused until the file changes on disk (Minecraft rewrites them on save and logout),
    // so rankings don't re-read every offline player's file on each command.
    private final java.util.Map<UUID, Cached> cache = new java.util.concurrent.ConcurrentHashMap<>();

    private record Cached(java.nio.file.attribute.FileTime modified, long size, Counters counters) {
    }
    // Built lazily on the main thread and only read there afterwards.
    private List<Material> mineableBlocks;

    public StatsReader(Path mainWorldFolder) {
        this.statsFolder = statsFolder(mainWorldFolder);
    }

    /**
     * Where the world keeps players' stats files: {@code players/stats} since Minecraft 26.1 moved player data under
     * {@code players/}, or the old {@code stats} folder on older servers.
     */
    public static Path statsFolder(Path mainWorldFolder) {
        // Paper 26 reports the overworld as world/dimensions/minecraft/overworld, while player stats live at the top of
        // the level (world/players/stats), so look in the folder and up to three levels above it.
        Path folder = mainWorldFolder;
        for (int up = 0; up <= 3 && folder != null; up++, folder = folder.getParent()) {
            Path current = folder.resolve("players").resolve("stats");
            if (java.nio.file.Files.isDirectory(current)) {
                return current;
            }
        }
        Path legacy = mainWorldFolder.resolve("stats");
        return java.nio.file.Files.isDirectory(legacy) ? legacy : mainWorldFolder.resolve("players").resolve("stats");
    }

    /** Main thread only. */
    public Counters live(Player player) {
        return rawLive(player);
    }

    private Counters rawLive(Player player) {
        long mined = 0;
        for (Material block : mineableBlocks(player)) {
            mined += player.getStatistic(Statistic.MINE_BLOCK, block);
        }
        return new Counters(
                player.getStatistic(Statistic.PLAY_ONE_MINUTE),
                player.getStatistic(Statistic.DEATHS),
                player.getStatistic(Statistic.MOB_KILLS),
                player.getStatistic(Statistic.PLAYER_KILLS),
                mined,
                player.getStatistic(Statistic.WALK_ONE_CM));
    }

    /**
     * Reads the vanilla stats file for an offline player. Going through
     * OfflinePlayer#getStatistic would reload this file once per block type,
     * which does not fit on the main thread, so the JSON is parsed directly.
     */
    public Counters fromFile(UUID uuid) throws IOException {
        Path file = statsFolder.resolve(uuid + ".json");
        if (!Files.exists(file)) {
            return Counters.EMPTY;
        }
        java.nio.file.attribute.FileTime modified = Files.getLastModifiedTime(file);
        long size = Files.size(file);
        Cached cached = cache.get(uuid);
        if (cached != null && cached.modified().equals(modified) && cached.size() == size) {
            return cached.counters();
        }
        Counters counters = parse(file);
        cache.put(uuid, new Cached(modified, size, counters));
        return counters;
    }

    private static Counters parse(Path file) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        JsonObject stats = child(root, "stats");
        JsonObject custom = child(stats, "minecraft:custom");
        long mined = 0;
        for (Map.Entry<String, JsonElement> entry : child(stats, "minecraft:mined").entrySet()) {
            mined += entry.getValue().getAsLong();
        }
        return new Counters(
                number(custom, "minecraft:play_time"),
                number(custom, "minecraft:deaths"),
                number(custom, "minecraft:mob_kills"),
                number(custom, "minecraft:player_kills"),
                mined,
                number(custom, "minecraft:walk_one_cm"));
    }

    @SuppressWarnings("deprecation")
    private List<Material> mineableBlocks(Player sample) {
        if (mineableBlocks == null) {
            List<Material> blocks = new ArrayList<>();
            for (Material material : Material.values()) {
                if (material.isLegacy() || !material.isBlock() || material.isAir()) {
                    continue;
                }
                try {
                    sample.getStatistic(Statistic.MINE_BLOCK, material);
                    blocks.add(material);
                } catch (IllegalArgumentException ignored) {
                    // Block with no mined statistic.
                }
            }
            mineableBlocks = List.copyOf(blocks);
        }
        return mineableBlocks;
    }

    private static JsonObject child(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static long number(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsLong() : 0;
    }
}
