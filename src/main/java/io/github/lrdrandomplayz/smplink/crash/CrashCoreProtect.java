package io.github.lrdrandomplayz.smplink.crash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import net.coreprotect.api.LookupOptions;
import net.coreprotect.api.result.ContainerResult;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Container withdrawals CoreProtect logged for a player, for the dupe check. Only touched through
 * {@link #withdrawals}, which survives CoreProtect being absent. Lookups query CoreProtect's database, so call
 * this off the main thread.
 */
final class CrashCoreProtect {

    private static final int MIN_API = 10;
    private static final int CONTAINER_REMOVE = 0;

    private CrashCoreProtect() {
    }

    /**
     * Warning lines such as "Took 12x DIAMOND from 3 containers since the save", for withdrawals by {@code name}
     * with {@code from <= time <= to} (epoch ms). Empty when CoreProtect is missing or finds nothing.
     */
    static List<String> withdrawals(String name, long from, long to, Logger logger) {
        List<String> lines = new ArrayList<>();
        Plugin plugin = Bukkit.getPluginManager().getPlugin("CoreProtect");
        if (plugin == null || !plugin.isEnabled()) {
            return lines;
        }
        try {
            return lookup(plugin, name, from, to);
        } catch (Throwable t) { // NoClassDefFoundError when CoreProtect isn't there, or an API change
            logger.warning("[crash] CoreProtect lookup failed: " + t);
            return lines;
        }
    }

    private static List<String> lookup(Plugin plugin, String name, long from, long to) {
        List<String> lines = new ArrayList<>();
        if (!(plugin instanceof CoreProtect coreProtect)) {
            return lines;
        }
        CoreProtectAPI api = coreProtect.getAPI();
        if (api == null || !api.isEnabled() || api.APIVersion() < MIN_API) {
            return lines;
        }
        int seconds = (int) Math.min(Integer.MAX_VALUE, (System.currentTimeMillis() - from) / 1000 + 120);
        List<ContainerResult> results = api.containerLookup(LookupOptions.builder().user(name).time(seconds).build());
        if (results == null) {
            return lines;
        }
        Map<String, int[]> byItem = new LinkedHashMap<>(); // material -> {amount, rows}
        for (ContainerResult result : results) {
            long at = result.getTimestamp() < 100_000_000_000L ? result.getTimestamp() * 1000 : result.getTimestamp();
            if (result.getActionId() != CONTAINER_REMOVE || result.getType() == null || at < from || at > to) {
                continue;
            }
            int[] total = byItem.computeIfAbsent(result.getType().name(), k -> new int[2]);
            total[0] += Math.max(1, result.getAmount());
            total[1]++;
        }
        byItem.forEach((material, total) -> lines.add("Took " + total[0] + "x " + material
                + " from containers after the save (" + total[1] + " withdrawal" + (total[1] == 1 ? "" : "s")
                + "); the containers were rolled back too, so they may have duplicated."));
        return lines;
    }
}
