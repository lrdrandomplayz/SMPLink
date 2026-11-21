package io.github.lrdrandomplayz.smplink.sync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import io.github.lrdrandomplayz.smplink.PluginSettings;
import io.github.lrdrandomplayz.smplink.db.LinkRepository;
import io.github.lrdrandomplayz.smplink.db.LinkRepository.ImportEntry;
import io.github.lrdrandomplayz.smplink.db.LinkRepository.ImportResult;
import io.github.lrdrandomplayz.smplink.db.LinkRepository.Removal;
import io.github.lrdrandomplayz.smplink.http.SyncBridge;
import io.github.lrdrandomplayz.smplink.service.PlayerService;
import io.github.lrdrandomplayz.smplink.service.PlayerService.KnownPlayer;
import io.github.lrdrandomplayz.smplink.sync.MojangResolver.Failed;
import io.github.lrdrandomplayz.smplink.sync.MojangResolver.Found;
import io.github.lrdrandomplayz.smplink.sync.MojangResolver.Lookup;
import io.github.lrdrandomplayz.smplink.sync.SheetParser.Edition;
import io.github.lrdrandomplayz.smplink.sync.SheetParser.Problem;
import io.github.lrdrandomplayz.smplink.sync.SheetParser.Row;

/** Imports links from the Google Sheet on a schedule, one run at a time, off the main thread. */
public final class SheetImporter {

    public record Sources(int server, int mojang, int offline) {
    }

    /** {@code whitelist} is null when whitelist sync is off. */
    public record Report(String trigger, String tab, long durationMs, int sent, Sources sources, int byUsername,
                         int withoutDiscord, List<Problem> invalidDiscord, List<Problem> invalidNames,
                         List<Problem> lookupFailed, List<Problem> duplicates, ImportResult result,
                         Removal removal, WhitelistSync.Result whitelist) {

        public int rejected() {
            return invalidDiscord.size() + invalidNames.size() + lookupFailed.size() + duplicates.size();
        }
    }

    private final PluginSettings settings;
    private final GoogleSheetsClient sheets;
    private final MojangResolver mojang;
    private final DiscordMembers members;
    private final LinkRepository links;
    private final PlayerService players;
    private final WhitelistSync whitelist;
    private final Logger logger;
    private final List<Consumer<Report>> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(named("SMPLink-Sync"));
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(named("SMPLink-SyncTimer"));
    private CompletableFuture<Report> current;

    public SheetImporter(PluginSettings settings, GoogleSheetsClient sheets, MojangResolver mojang,
                         DiscordMembers members, LinkRepository links, PlayerService players, WhitelistSync whitelist,
                         Logger logger) {
        this.whitelist = whitelist;
        this.settings = settings;
        this.sheets = sheets;
        this.mojang = mojang;
        this.members = members;
        this.links = links;
        this.players = players;
        this.logger = logger;
    }

    /** Runs once now and then every sync interval. Call once the server is ticking, since lookups need the main thread. */
    public void start() {
        long interval = settings.syncIntervalMinutes();
        timer.scheduleWithFixedDelay(() -> runLogged("scheduled"), interval, interval, TimeUnit.MINUTES);
        runLogged("startup");
    }

    /** Called on the sync thread after every successful import. */
    public void addListener(Consumer<Report> listener) {
        listeners.add(listener);
    }

    public void stop() {
        timer.shutdownNow();
        worker.shutdownNow();
    }

    /** Starts an import, or returns the one already running so two can't overlap. */
    public synchronized CompletableFuture<Report> run(String trigger) {
        if (current == null || current.isDone()) {
            current = CompletableFuture.supplyAsync(() -> {
                try {
                    return importNow(trigger);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, worker);
        }
        return current;
    }

    /**
     * Like {@link #run}, but an import already in progress may have read the sheet before an edit, so this
     * waits for it to finish and then starts a new one.
     */
    public CompletableFuture<Report> runFresh(String trigger) {
        CompletableFuture<Report> previous;
        synchronized (this) {
            previous = current;
        }
        CompletableFuture<Void> ready = previous == null
                ? CompletableFuture.completedFuture(null)
                : previous.handle((report, error) -> null);
        return ready.thenCompose(ignored -> run(trigger));
    }

    private void runLogged(String trigger) {
        run(trigger).exceptionally(error -> {
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            logger.log(Level.WARNING, "[import] " + trigger + " import failed: " + cause.getMessage());
            return null;
        });
    }

    private Report importNow(String trigger) throws Exception {
        long startedAt = System.currentTimeMillis();
        logger.info("[import] starting (" + trigger + ")");

        GoogleSheetsClient.Sheet sheet = sheets.read(settings.sheetId(), settings.sheetTab());
        SheetParser.Result parsed = SheetParser.parse(sheet.rows());
        List<Problem> invalidDiscord = new ArrayList<>(parsed.invalidDiscord());
        List<Problem> invalidNames = new ArrayList<>();
        List<Problem> lookupFailed = new ArrayList<>();
        List<Problem> duplicates = new ArrayList<>();

        int byUsername = 0;
        for (Row row : parsed.rows()) {
            if (row.discordUsername == null) {
                continue;
            }
            try {
                row.discordId = members.findByUsername(row.discordUsername);
            } catch (IOException e) {
                lookupFailed.add(new Problem(row.row, row.ign, row.discordUsername,
                        "Discord lookup of \"" + row.discordUsername + "\" failed: " + e.getMessage()));
                continue;
            }
            if (row.discordId != null) {
                byUsername++;
            } else {
                invalidDiscord.add(new Problem(row.row, row.ign, row.discordUsername,
                        "does not match any member of the Discord server"));
            }
        }

        List<Row> unique = new ArrayList<>();
        Map<String, Row> byDiscord = new HashMap<>();
        Map<String, Row> byName = new HashMap<>();
        for (Row row : parsed.rows()) {
            if (row.discordId == null) {
                continue;
            }
            if (!MojangResolver.VALID_NAME.matcher(row.ign).matches()) {
                invalidNames.add(new Problem(row.row, row.ign, row.ign, "not a valid Minecraft username"));
                continue;
            }
            String nameKey = row.ign.toLowerCase(Locale.ROOT);
            Row first = byDiscord.containsKey(row.discordId) ? byDiscord.get(row.discordId) : byName.get(nameKey);
            if (first != null) {
                String what = byDiscord.containsKey(row.discordId) ? "Discord ID" : "username";
                duplicates.add(new Problem(row.row, row.ign, row.ign, "same " + what + " as row " + first.row));
                continue;
            }
            byDiscord.put(row.discordId, row);
            byName.put(nameKey, row);
            unique.add(row);
        }

        Map<String, KnownPlayer> known = unique.isEmpty()
                ? Map.of()
                : players.knownPlayers(unique.stream().map(r -> r.ign).toList());
        List<ImportEntry> entries = new ArrayList<>();
        int fromServer = 0;
        int fromMojang = 0;
        int offline = 0;
        for (Row row : unique) {
            KnownPlayer onServer = known.get(row.ign.toLowerCase(Locale.ROOT));
            if (onServer != null) {
                entries.add(new ImportEntry(row.discordId, onServer.uuid(), onServer.name(), row.table == 0));
                fromServer++;
                continue;
            }
            if (row.edition == Edition.CRACKED) {
                entries.add(new ImportEntry(row.discordId, MojangResolver.offlineUuid(row.ign), row.ign, row.table == 0));
                offline++;
                continue;
            }
            Lookup lookup = mojang.resolve(row.ign);
            if (lookup instanceof Found found) {
                entries.add(new ImportEntry(row.discordId, found.uuid(), found.name(), row.table == 0));
                fromMojang++;
            } else if (lookup instanceof Failed failed) {
                lookupFailed.add(new Problem(row.row, row.ign, row.ign, failed.message()));
            } else if (row.edition == Edition.PREMIUM) {
                invalidNames.add(new Problem(row.row, row.ign, row.ign, "marked premium but Mojang has no such account"));
            } else {
                // Hybrid server: a name Mojang doesn't know can only be a cracked player.
                UUID uuid = MojangResolver.offlineUuid(row.ign);
                entries.add(new ImportEntry(row.discordId, uuid, row.ign, row.table == 0));
                offline++;
            }
        }
        mojang.save();

        // LinkRepository logs every link change with the Discord ID and UUID.
        ImportResult result = SyncBridge.await(links.importEntries(entries, settings.allowRelinking()),
                PlayerService.IMPORT_TIMEOUT_MS);
        for (Problem problem : invalidDiscord) {
            logger.warning("[import] skipped row " + problem.row() + " (" + problem.ign() + "): Discord value \""
                    + problem.value() + "\" " + problem.reason());
        }
        for (Problem problem : concat(invalidNames, lookupFailed, duplicates)) {
            logger.warning("[import] skipped row " + problem.row() + " (" + problem.ign() + "): " + problem.reason());
        }

        Set<String> sheetDiscordIds = new HashSet<>();
        parsed.rows().forEach(row -> {
            if (row.discordId != null) {
                sheetDiscordIds.add(row.discordId);
            }
        });
        Set<String> sheetNames = new HashSet<>();
        parsed.allNames().forEach(name -> sheetNames.add(name.toLowerCase(Locale.ROOT)));
        Removal removal = settings.removeMissingLinks()
                ? SyncBridge.await(links.removeMissing(sheetDiscordIds, sheetNames, 0.5), PlayerService.DB_TIMEOUT_MS)
                : new Removal(List.of(), null);

        WhitelistSync.Result whitelisted = null;
        if (whitelist != null) {
            try {
                whitelisted = whitelist.sync(parsed.allNames());
            } catch (Exception e) {
                logger.log(Level.WARNING, "[whitelist] sync failed", e);
                whitelisted = new WhitelistSync.Result(List.of(), List.of(), null, e.getMessage());
            }
        }

        Report report = new Report(trigger, sheet.tab(), System.currentTimeMillis() - startedAt, entries.size(),
                new Sources(fromServer, fromMojang, offline), byUsername, parsed.withoutDiscord(),
                invalidDiscord, invalidNames, lookupFailed, duplicates, result, removal, whitelisted);
        logger.info("[import] done in " + report.durationMs() + " ms: " + report.rejected() + " rows rejected, "
                + parsed.withoutDiscord() + " rows without a Discord ID");
        for (Consumer<Report> listener : listeners) {
            try {
                listener.accept(report);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "An import listener failed", e);
            }
        }
        return report;
    }

    @SafeVarargs
    private static List<Problem> concat(List<Problem>... lists) {
        List<Problem> all = new ArrayList<>();
        for (List<Problem> list : lists) {
            all.addAll(list);
        }
        return all;
    }

    private static java.util.concurrent.ThreadFactory named(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
