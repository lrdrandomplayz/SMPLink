package io.github.lrdrandomplayz.smplink.sync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import io.github.lrdrandomplayz.smplink.PluginSettings;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository;
import io.github.lrdrandomplayz.smplink.http.SyncBridge;
import io.github.lrdrandomplayz.smplink.service.PlayerService;
import org.bukkit.Bukkit;

/**
 * Keeps the server whitelist in step with the sheet by running the configured whitelist plugin's
 * commands (NobleWhitelist's by default) from the console. Only names this class added are ever
 * removed, so players an admin whitelisted by hand are left alone.
 */
public final class WhitelistSync {

    /** {@code error} is set when the commands could not run at all (for example the plugin is missing). */
    public record Result(List<String> added, List<String> removed, String skippedRemovals, String error) {
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final PluginSettings settings;
    private final TrackingRepository tracking;
    private final SyncBridge sync;
    private final Logger logger;

    public WhitelistSync(PluginSettings settings, TrackingRepository tracking, SyncBridge sync, Logger logger) {
        this.settings = settings;
        this.tracking = tracking;
        this.sync = sync;
        this.logger = logger;
    }

    /** Call off the main thread. */
    public Result sync(List<String> sheetNames) throws Exception {
        Map<String, String> wanted = new LinkedHashMap<>();
        for (String name : sheetNames) {
            String trimmed = name.trim();
            if (NAME.matcher(trimmed).matches()) {
                wanted.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
            }
        }
        Map<String, String> tracked = SyncBridge.await(tracking.whitelisted(), PlayerService.DB_TIMEOUT_MS);

        Map<String, String> toAdd = new LinkedHashMap<>(wanted);
        toAdd.keySet().removeAll(tracked.keySet());
        Map<String, String> toRemove = new LinkedHashMap<>(tracked);
        toRemove.keySet().removeAll(wanted.keySet());

        String skipped = null;
        if (toRemove.size() > Math.max(3, tracked.size() / 2)) {
            skipped = "the sheet no longer lists " + toRemove.size() + " of " + tracked.size()
                    + " whitelisted names, which looks like a broken or emptied sheet, so nobody was removed";
            logger.warning("[whitelist] " + skipped);
            toRemove.clear();
        }
        if (toAdd.isEmpty() && toRemove.isEmpty()) {
            return new Result(List.of(), List.of(), skipped, null);
        }

        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        String error = sync.call(() -> {
            for (Map.Entry<String, String> entry : toAdd.entrySet()) {
                if (!run(settings.whitelistAddCommand(), entry.getValue())) {
                    return "the server did not recognise \"" + command(settings.whitelistAddCommand(), entry.getValue())
                            + "\" (is NobleWhitelist installed?)";
                }
                added.add(entry.getValue());
            }
            for (Map.Entry<String, String> entry : toRemove.entrySet()) {
                if (!run(settings.whitelistRemoveCommand(), entry.getValue())) {
                    return "the server did not recognise \"" + command(settings.whitelistRemoveCommand(), entry.getValue()) + "\"";
                }
                removed.add(entry.getValue());
            }
            return null;
        });

        Map<String, String> addedByKey = new LinkedHashMap<>();
        added.forEach(name -> addedByKey.put(name.toLowerCase(Locale.ROOT), name));
        SyncBridge.await(tracking.recordWhitelisted(addedByKey,
                removed.stream().map(name -> name.toLowerCase(Locale.ROOT)).toList()), PlayerService.DB_TIMEOUT_MS);

        added.forEach(name -> logger.info("[whitelist] added " + name));
        removed.forEach(name -> logger.info("[whitelist] removed " + name + " (no longer in the sheet)"));
        if (error != null) {
            logger.warning("[whitelist] stopped: " + error);
        }
        return new Result(added, removed, skipped, error);
    }

    private static boolean run(String template, String name) {
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command(template, name));
    }

    private static String command(String template, String name) {
        String command = template.replace("{name}", name);
        return command.startsWith("/") ? command.substring(1) : command;
    }
}
