package io.github.lrdrandomplayz.smplink;

import io.github.lrdrandomplayz.smplink.command.JoinLockConsoleCommand;
import io.github.lrdrandomplayz.smplink.command.LinkInfoCommand;
import io.github.lrdrandomplayz.smplink.command.SyncCommand;
import io.github.lrdrandomplayz.smplink.crash.CrashCaseRepository;
import io.github.lrdrandomplayz.smplink.crash.CrashJoinListener;
import io.github.lrdrandomplayz.smplink.crash.CrashRecovery;
import io.github.lrdrandomplayz.smplink.crash.CrashSettings;
import io.github.lrdrandomplayz.smplink.crash.CrashSnapshotRepository;
import io.github.lrdrandomplayz.smplink.crash.CrashSnapshotter;
import io.github.lrdrandomplayz.smplink.crash.HealthMonitor;
import io.github.lrdrandomplayz.smplink.crash.ShutdownMarker;
import io.github.lrdrandomplayz.smplink.db.DailyBackup;
import io.github.lrdrandomplayz.smplink.db.Database;
import io.github.lrdrandomplayz.smplink.db.InventoryRepository;
import io.github.lrdrandomplayz.smplink.db.LinkRepository;
import io.github.lrdrandomplayz.smplink.db.SessionRepository;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository;
import io.github.lrdrandomplayz.smplink.db.TradeRepository;
import io.github.lrdrandomplayz.smplink.discord.CrashCards;
import io.github.lrdrandomplayz.smplink.discord.DiscordBot;
import io.github.lrdrandomplayz.smplink.discord.FirstJoinListener;
import io.github.lrdrandomplayz.smplink.discord.InventoryRenderer;
import io.github.lrdrandomplayz.smplink.guard.JoinLock;
import io.github.lrdrandomplayz.smplink.http.SyncBridge;
import io.github.lrdrandomplayz.smplink.inventory.InventorySnapshotter;
import io.github.lrdrandomplayz.smplink.link.LinkCodes;
import io.github.lrdrandomplayz.smplink.link.LinkGameCommand;
import io.github.lrdrandomplayz.smplink.service.PlayerService;
import io.github.lrdrandomplayz.smplink.stats.SessionTracker;
import io.github.lrdrandomplayz.smplink.stats.StatsReader;
import io.github.lrdrandomplayz.smplink.stats.WarningReminder;
import io.github.lrdrandomplayz.smplink.sync.DiscordMembers;
import io.github.lrdrandomplayz.smplink.sync.GoogleSheetsClient;
import io.github.lrdrandomplayz.smplink.sync.MojangResolver;
import io.github.lrdrandomplayz.smplink.sync.SheetEditor;
import io.github.lrdrandomplayz.smplink.sync.SheetImporter;
import io.github.lrdrandomplayz.smplink.sync.WhitelistSync;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.dv8tion.jda.api.JDA;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;

public final class SMPLinkPlugin extends JavaPlugin {

    private Database database;
    private InventorySnapshotter snapshotter;
    private SessionTracker sessionTracker;
    private DailyBackup backup;
    private SheetImporter importer;
    private DiscordBot bot;
    private CrashSnapshotter crashSnapshotter;
    private HealthMonitor healthMonitor;
    private ShutdownMarker shutdownMarker;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        PluginSettings settings;
        try {
            settings = PluginSettings.load(this);
        } catch (IllegalArgumentException e) {
            getLogger().severe("Invalid config.yml: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Branding.set(getConfig().getString("server.name", ""), getConfig().getString("server.tagline", ""));

        Path databaseFile = getDataFolder().toPath().resolve("data.db").toAbsolutePath();
        database = new Database(databaseFile);
        database.open().exceptionally(error -> {
            getLogger().log(Level.SEVERE, "Could not open plugins/SMPLink/data.db, disabling", error);
            getServer().getScheduler().runTask(this, () -> getServer().getPluginManager().disablePlugin(this));
            return null;
        });

        LinkRepository links = new LinkRepository(database, getLogger());
        InventoryRepository inventories = new InventoryRepository(database);
        StatsReader stats = new StatsReader(getServer().getWorlds().get(0).getWorldFolder().toPath());
        SessionRepository sessions = new SessionRepository(database);
        TrackingRepository tracking = new TrackingRepository(database);
        SyncBridge sync = new SyncBridge(this);
        PlayerService players = new PlayerService(links, sessions, inventories, stats, sync, getLogger());
        // The weekly leaderboard starts over when the weekly recap posts.
        players.setWeekReset(settings.recapDay(), settings.recapTime(), settings.statusZone());

        // Keeps everyone but staff out until an opening time (/joinlock).
        JoinLock joinLock = new JoinLock(this, tracking, links, settings.statusZone(), getLogger());
        getServer().getPluginManager().registerEvents(joinLock, this);
        joinLock.start();
        register("joinlock", new JoinLockConsoleCommand(this, joinLock));

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        snapshotter = new InventorySnapshotter(this, inventories);
        getServer().getPluginManager().registerEvents(snapshotter, this);
        snapshotter.start(settings.inventoryCacheIntervalSeconds());

        if (settings.backupEnabled()) {
            backup = new DailyBackup(database, getDataFolder().toPath().resolve("backups"), settings.backupKeep(), getLogger());
            backup.start();
        }

        sessionTracker = new SessionTracker(this, sessions);
        getServer().getPluginManager().registerEvents(sessionTracker, this);
        sessionTracker.start();

        // Crash recovery: player snapshots and the restore cases staff work through after a crash.
        CrashSettings crashSettings = CrashSettings.from(getConfig().getConfigurationSection("crash-recovery"));
        CrashSnapshotRepository crashSnapshots = new CrashSnapshotRepository(database);
        CrashCaseRepository crashCases = new CrashCaseRepository(database);
        ShutdownMarker.CrashInfo crash = null;
        try {
            for (CompletableFuture<Void> init : List.of(crashSnapshots.init(), crashCases.init())) {
                init.get(30, TimeUnit.SECONDS);
            }
            if (crashSettings.enabled()) {
                shutdownMarker = new ShutdownMarker(tracking, crashSnapshots);
                crash = shutdownMarker.begin().get(30, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not set up the crash-recovery tables", e);
        }
        String staffChannel = crashSettings.staffChannelId().isEmpty() ? settings.auditChannelId() : crashSettings.staffChannelId();
        CrashRecovery recovery = new CrashRecovery(this, crashSnapshots, crashCases,
                c -> whenBotReady(jda -> CrashCards.post(jda, staffChannel, c, getLogger())));

        // Linking: in-game codes (default), or links read from a Google Sheet.
        String linkingMode = getConfig().getString("linking.mode", "codes").toLowerCase(Locale.ROOT);
        boolean sheetMode = linkingMode.equals("sheet");
        SheetEditor editor = null;
        if (sheetMode && settings.sheetEnabled()) {
            GoogleSheetsClient sheets = new GoogleSheetsClient(http, settings.sheetKeyFile());
            MojangResolver mojang = new MojangResolver(http, getDataFolder().toPath().resolve("mojang-cache.json"), getLogger());
            editor = new SheetEditor(settings, sheets, mojang);
            importer = new SheetImporter(settings, sheets, mojang,
                    new DiscordMembers(http, settings.discordToken(), settings.guildId()),
                    links, players,
                    settings.whitelistEnabled() ? new WhitelistSync(settings, tracking, sync, getLogger()) : null,
                    getLogger());
            // runTask fires on the first tick, once the server is up and can answer main-thread lookups.
            getServer().getScheduler().runTask(this, importer::start);
        } else if (sheetMode) {
            getLogger().warning("linking.mode is sheet, but sheet.id is empty; nobody can link until it's set");
        }
        LinkCodes codes = null;
        if (!sheetMode) {
            codes = new LinkCodes(Math.max(1, getConfig().getInt("linking.code-minutes", 10)) * 60_000L);
            register("link", new LinkGameCommand(this, codes, links, getLogger()));
        }

        if (settings.discordEnabled()) {
            try {
                InventoryRenderer renderer = new InventoryRenderer(http, InventoryRenderer.serverItemIds());
                Path icons = iconFolder();
                renderer.setIconFolder(icons);
                io.github.lrdrandomplayz.smplink.discord.DiscordBot.setIconFolder(icons);
                bot = new DiscordBot(settings, players, importer, renderer, tracking,
                        new TradeRepository(database), editor, bannerFolder(), getLogger());
                if (codes != null) {
                    bot.setLinkCodes(codes, links);
                    bot.setRoster(new io.github.lrdrandomplayz.smplink.sync.ServerRoster(settings, links, sync, getLogger()));
                }
                bot.setJoinLock(joinLock);
                DiscordBot connected = bot;
                bot.setCrashCards(new CrashCards(recovery, connected::isAdminMember,
                        task -> getServer().getScheduler().runTask(this, task), getLogger()));
                bot.setApplications(getConfig().getString("applications.review-channel-id", "off"),
                        getConfig().getString("applications.staff-role-id", "off"),
                        getConfig().getString("applications.rules-channel-id", "off"),
                        getConfig().getString("applications.rules-message-id", "off"),
                        getConfig().getString("applications.rules-emoji", "✅"),
                        getConfig().getInt("applications.reapply-days", 3));
                bot.start();
            } catch (RuntimeException e) {
                getLogger().log(Level.SEVERE, "Could not set up the Discord bot; it's off", e);
                bot = null;
            }
        }

        if (bot != null) {
            getServer().getPluginManager().registerEvents(new FirstJoinListener(bot, links, getLogger()), this);
        }

        // Crash recovery: snapshots every few seconds, restore cards after a crash, and the health monitor.
        if (crashSettings.enabled()) {
            getServer().getPluginManager().registerEvents(new CrashJoinListener(recovery), this);
            crashSnapshotter = new CrashSnapshotter(crashSnapshots, crashSettings, getLogger());
            crashSnapshotter.start(this);
            if (crash != null) {
                long crashedAt = crash.crashedAt();
                long backAt = System.currentTimeMillis();
                getLogger().warning("[crash] The server didn't shut down cleanly last time (last sign of life "
                        + Instant.ofEpochMilli(crashedAt) + "); checking who lost items as they rejoin.");
                recovery.begin(crash).thenAccept(count -> whenBotReady(jda -> CrashCards.postCrashNotice(
                        jda, crashSettings.announceChannelId(), crashedAt, backAt, count, getLogger())));
            }
            if (crashSettings.healthEnabled()) {
                healthMonitor = new HealthMonitor(this, crashSettings,
                        text -> whenBotReady(jda -> bot.postText(staffChannel, "crash-recovery.staff-channel-id", text)));
                healthMonitor.start();
            }
        }
        if (settings.ingameReminders()) {
            WarningReminder reminder = new WarningReminder(this, settings, links, tracking, sessions);
            getServer().getPluginManager().registerEvents(reminder, this);
            reminder.start();
        }

        register("linkinfo", new LinkInfoCommand(this, links));
        register("smplink", new SyncCommand(this, importer));
    }

    /** Runs {@code action} once the bot is logged in, checking every 15 s for up to 10 minutes; never on the main thread. */
    private void whenBotReady(Consumer<JDA> action) {
        whenBotReady(action, 40);
    }

    private void whenBotReady(Consumer<JDA> action, int triesLeft) {
        DiscordBot current = bot;
        JDA jda = current == null ? null : current.jda();
        if (jda != null) {
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    action.accept(jda);
                } catch (RuntimeException e) {
                    getLogger().log(Level.WARNING, "Discord post failed", e);
                }
            });
        } else if (current != null && triesLeft > 0 && isEnabled()) {
            getServer().getScheduler().runTaskLaterAsynchronously(this, () -> whenBotReady(action, triesLeft - 1), 20L * 15);
        }
    }

    /**
     * plugins/SMPLink/banners, where /profile looks for stats.png, playtime.png and strikes.png. The
     * banners in the jar are copied there once; after that the folder is the admin's to change or empty.
     */
    private Path bannerFolder() {
        Path folder = getDataFolder().toPath().resolve("banners");
        Path marker = folder.resolve(".defaults-installed");
        try {
            Files.createDirectories(folder);
            if (!Files.exists(marker)) {
                for (String page : new String[]{"stats", "playtime", "strikes"}) {
                    Path target = folder.resolve(page + ".png");
                    if (!Files.exists(target)) {
                        try (InputStream in = getResource("banners/" + page + ".png")) {
                            if (in != null) {
                                Files.copy(in, target);
                            }
                        }
                    }
                }
                Files.writeString(marker, "The default banners were copied here once. Replace or delete them freely.\n");
            }
        } catch (IOException e) {
            getLogger().warning("Could not set up " + folder + "; /profile may have no banners: " + e.getMessage());
        }
        return folder;
    }

    /** plugins/SMPLink/icons: item pictures for /inv and trade posts, named like diamond_sword.png. */
    private Path iconFolder() {
        Path folder = getDataFolder().toPath().resolve("icons");
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            getLogger().warning("Could not create " + folder + ": " + e.getMessage());
        }
        try (var files = Files.list(folder)) {
            if (files.noneMatch(f -> f.toString().endsWith(".png"))) {
                getLogger().info("No item icons in " + folder + ", so /inv and trade pictures show placeholder squares. See the README.");
            }
        } catch (IOException ignored) {
            // Only a hint; rendering copes without the folder.
        }
        return folder;
    }

    @Override
    public void onDisable() {
        if (bot != null) {
            bot.stop();
        }
        if (importer != null) {
            importer.stop();
        }
        if (backup != null) {
            backup.stop();
        }
        if (sessionTracker != null) {
            sessionTracker.stop();
        }
        if (crashSnapshotter != null) {
            crashSnapshotter.stop();
        }
        if (healthMonitor != null) {
            healthMonitor.stop(); // puts back any view distance it lowered
        }
        if (snapshotter != null) {
            // Plugins are disabled before players are kicked at shutdown, so the quit event would come too late.
            snapshotter.saveAll();
        }
        if (shutdownMarker != null) {
            try {
                shutdownMarker.markClean().get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                getLogger().warning("Could not record the clean shutdown; the next start may report a crash: " + e);
            }
        }
        if (database != null) {
            database.close();
        }
    }

    private void register(String name, TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
    }
}
