package io.github.lrdrandomplayz.smplink.discord;

import io.github.lrdrandomplayz.smplink.PluginSettings;
import io.github.lrdrandomplayz.smplink.db.LinkRepository;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository;
import io.github.lrdrandomplayz.smplink.db.TradeRepository;
import io.github.lrdrandomplayz.smplink.link.LinkCodes;
import io.github.lrdrandomplayz.smplink.service.PlayerService;
import io.github.lrdrandomplayz.smplink.sync.SheetEditor;
import io.github.lrdrandomplayz.smplink.sync.SheetImporter;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.requests.GatewayIntent;

/** Runs the Discord bot inside the plugin, the way DiscordSRV does. */
public final class DiscordBot {

    private final PluginSettings settings;
    private final BotListener listener;
    private final ActivityCheck activityCheck;
    private final MemberSync memberSync;
    private final MilestoneAnnouncer milestones;
    private final WeeklyRecap recap;
    private final ActivityEnforcer enforcer;
    private final WelcomePoster welcome;
    private final TradeBoard trades;
    private final TradeReviews reviews;
    private final StrikeService strikeService;
    private final WhitelistCommand whitelist;
    private final PlayerService players;
    private final TrackingRepository tracking;
    private final Logger logger;
    // Weekly schedules, milestone checks and member syncs; one thread so they never overlap.
    private final ScheduledExecutorService tasks = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SMPLink-Tasks");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService statusTimer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SMPLink-Status");
        thread.setDaemon(true);
        return thread;
    });
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private volatile JDA jda;

    public DiscordBot(PluginSettings settings, PlayerService players, SheetImporter importer, InventoryRenderer renderer,
                      TrackingRepository tracking, TradeRepository tradeRepository, SheetEditor editor,
                      java.nio.file.Path bannerFolder, Logger logger) {
        this.settings = settings;
        this.logger = logger;
        this.players = players;
        this.tracking = tracking;
        Strikes strikes = new Strikes(tracking, settings);
        this.strikeService = new StrikeService(settings, tracking, strikes, editor, importer, logger);
        this.whitelist = new WhitelistCommand(editor, importer, tracking, settings, logger);
        this.activityCheck = new ActivityCheck(players, tracking, settings, strikes,
                new ActivityPause(tracking, settings.statusZone(), logger), logger);
        this.enforcer = new ActivityEnforcer(settings, players, tracking, strikes, strikeService, activityCheck, logger);
        this.memberSync = new MemberSync(settings, players, logger);
        this.milestones = new MilestoneAnnouncer(settings, players, tracking, logger);
        this.recap = new WeeklyRecap(settings, players, tracking, logger);
        this.trades = new TradeBoard(players, tradeRepository, renderer, settings, logger);
        this.reviews = new TradeReviews(trades, tradeRepository, logger);
        this.listener = new BotListener(players, importer, renderer, settings, activityCheck,
                whitelist,
                new LookupCommand(players, tracking, editor, activityCheck, strikes, logger),
                new WarningsCommand(players, tracking, activityCheck, strikes), trades, reviews, strikes,
                new ProfileCommand(players, tracking, reviews, strikes, bannerFolder),
                new StrikeCommand(players, strikeService, settings), tracking, logger);
        this.welcome = new WelcomePoster(settings, logger);
        if (importer != null) {
            // Role and nickname changes follow each sheet import, including players it unlinked.
            importer.addListener(report -> tasks.execute(() -> guarded("member sync",
                    () -> memberSync.run(jda, unlinked(report)))));
            importer.addListener(report -> tasks.execute(() -> guarded("welcome posts",
                    () -> welcome.welcomeNewLinks(jda, report.result().changes()))));
        }
    }

    /** The member list without a sheet (linking.mode: codes), for /whitelist and strike removals. Call before start(). */
    public void setRoster(Roster roster) {
        whitelist.setRoster(roster);
        strikeService.setRoster(roster);
    }

    /** Where the bot's Minecraft-item icons come from (plugins/SMPLink/icons). Call before start(). */
    public static void setIconFolder(java.nio.file.Path folder) {
        GlyphArt.setIconFolder(folder);
    }

    /** Turns on /link and /unlink (linking.mode: codes). Call before start(). */
    public void setLinkCodes(LinkCodes codes, LinkRepository links) {
        listener.setLinks(new LinkCommand(codes, links,
                changes -> tasks.execute(() -> {
                    guarded("welcome posts", () -> welcome.welcomeNewLinks(jda, changes));
                    guarded("member sync", () -> memberSync.run(jda, List.of()));
                }),
                removed -> tasks.execute(() -> guarded("member sync", () -> memberSync.run(jda, List.of(removed)))),
                logger));
    }

    /** Everyone a sheet import unlinked: players taken off the sheet, and accounts replaced by a relink. */
    static List<LinkRepository.Link> unlinked(SheetImporter.Report report) {
        List<LinkRepository.Link> out = new java.util.ArrayList<>(report.removal().removed());
        for (LinkRepository.Change change : report.result().changes()) {
            if (change.action().equals("unlinked")) {
                out.add(new LinkRepository.Link(change.uuid(), change.discordId(), change.name(), 0, false));
            }
        }
        return out;
    }

    /** Join applications (applications.* in config.yml). Call before start(). */
    public void setApplications(String reviewChannelId, String staffRoleId, String rulesChannelId, String rulesMessageId,
                                String rulesEmoji, int reapplyDays) {
        listener.setApplications(new JoinApplications(new JoinApplications.Settings(reviewChannelId, staffRoleId, rulesChannelId,
                rulesMessageId, rulesEmoji, reapplyDays), settings, players, tracking, listener.whitelist(), logger));
    }

    /** Crash restore cards. Call before start(). */
    public void setCrashCards(CrashCards crashCards) {
        listener.setCrashCards(crashCards);
    }

    /** True for the server owner and admins (crash restore cards). */
    public boolean isAdminMember(net.dv8tion.jda.api.entities.Member member) {
        return member != null && (member.isOwner()
                || member.getRoles().stream().anyMatch(role -> role.getId().equals(settings.adminRoleId())));
    }

    /** Posts plain text to a channel (staff health alerts). Does nothing while the bot is logging in. */
    public void postText(String channelId, String configKey, String text) {
        JDA current = jda;
        if (current == null || channelId == null || channelId.isBlank()) {
            return;
        }
        net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel channel = Channels.find(current, channelId, configKey, logger);
        if (channel != null) {
            channel.sendMessage(text).setAllowedMentions(List.of()).queue(null,
                    error -> logger.warning("Could not post to " + configKey + ": " + error.getMessage()));
        }
    }

    public void setJoinLock(io.github.lrdrandomplayz.smplink.guard.JoinLock lock) {
        listener.setJoinLock(new JoinLockCommand(lock));
        lock.setDiscordStaff(this::isStaff);
    }

    /** True for the Discord server's owner and admins. Blocks on Discord, so call it off the main thread. */
    public boolean isStaff(String discordId) {
        JDA current = jda;
        Guild guild = current == null ? null : current.getGuildById(settings.guildId());
        if (guild == null) {
            return false;
        }
        if (discordId.equals(guild.getOwnerId())) {
            return true;
        }
        try {
            return guild.retrieveMemberById(discordId).complete().getRoles().stream()
                    .anyMatch(role -> role.getId().equals(settings.adminRoleId()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Logs in on a background thread so a slow Discord connection never holds up server startup. */
    public void start() {
        Thread thread = new Thread(() -> {
            try {
                jda = JDABuilder.createLight(settings.discordToken(), EnumSet.noneOf(GatewayIntent.class))
                        .addEventListeners(listener)
                        .build()
                        .awaitReady();
                logger.info("Discord bot logged in as " + jda.getSelfUser().getName());
                Embeds.setBrandIcon(jda.getSelfUser().getEffectiveAvatarUrl());
                startStatusClock();
                startScheduledTasks();

                Guild guild = jda.getGuildById(settings.guildId());
                if (guild == null) {
                    logger.severe("The bot is not in the guild " + settings.guildId() + " (discord.guild-id). Invite it first.");
                    return;
                }
                // Replaces the whole guild command list, so commands removed from the code disappear from Discord.
                // One invalid definition stops the whole update, so say so plainly instead of blaming the token.
                List<net.dv8tion.jda.api.interactions.commands.build.CommandData> commands;
                try {
                    commands = BotListener.commands();
                } catch (RuntimeException e) {
                    logger.log(Level.SEVERE, "Could not build the Discord commands, so none were updated", e);
                    return;
                }
                // The bot's own icons (Glyph): uploaded on the first start, then reused. Messages use normal emojis until then.
                Thread icons = new Thread(() -> guarded("icons", () -> Glyph.install(jda, logger)), "SMPLink-Icons");
                icons.setDaemon(true);
                icons.start();
                guild.updateCommands().addCommands(commands).queue(
                        done -> logger.info("Registered " + done.size() + " Discord commands in " + guild.getName()),
                        error -> logger.severe("Could not register Discord commands: " + error.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "Discord bot failed to start (check discord.token in config.yml)", e);
            }
        }, "SMPLink-DiscordLogin");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Bots can't show rich-presence images or timers, so the current time goes into the status text
     * itself, refreshed at the start of every minute (well inside Discord's presence rate limit).
     */
    private void startStatusClock() {
        if (settings.statusText().isEmpty()) {
            return;
        }
        Runnable update = () -> {
            JDA current = jda;
            if (current == null) {
                return;
            }
            // A scheduled task that throws is never run again, so a failed update must not escape.
            try {
                ZonedDateTime now = ZonedDateTime.now(settings.statusZone());
                current.getPresence().setActivity(Activity.customStatus(
                        settings.statusText() + " · " + CLOCK.format(now) + " " + zoneLabel()));
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Could not update the bot status", e);
            }
        };
        update.run();
        long msToNextMinute = 60_000 - (System.currentTimeMillis() % 60_000);
        statusTimer.scheduleAtFixedRate(update, msToNextMinute, 60_000, TimeUnit.MILLISECONDS);
    }

    private String zoneLabel() {
        String id = settings.statusZone().getId();
        // "GMT+8" reads better than the normalised "GMT+08:00".
        return id.replaceAll(":00$", "").replaceAll("([+-])0([0-9])", "$1$2");
    }

    /**
     * The first runs wait a minute: lookups go through the main thread, which is busy while the server
     * finishes starting.
     */
    private void startScheduledTasks() {
        if (memberSync.enabled()) {
            tasks.schedule(() -> guarded("member sync", () -> memberSync.run(jda, List.of())), 90, TimeUnit.SECONDS);
        }
        if (milestones.enabled()) {
            tasks.scheduleWithFixedDelay(() -> guarded("milestone check", () -> milestones.check(jda)), 60, 300, TimeUnit.SECONDS);
        }
        if (recap.enabled()) {
            tasks.schedule(() -> guarded("recap snapshot", recap::ensureBaseline), 75, TimeUnit.SECONDS);
            WeeklySchedule.schedule(tasks, "Weekly recap", settings.recapDay(), settings.recapTime(), settings.statusZone(),
                    () -> guarded("weekly recap", () -> recap.post(jda)), logger);
        }
        // Follows up open warnings: congratulations as soon as the quota is met, removal after the deadline.
        tasks.scheduleWithFixedDelay(() -> guarded("activity follow-up", () -> enforcer.check(jda)), 120, 300, TimeUnit.SECONDS);
        if (!settings.tradeChannelId().isEmpty()) {
            tasks.scheduleWithFixedDelay(() -> guarded("trade expiry", () -> trades.expireOld(jda)), 180, 900, TimeUnit.SECONDS);
        }
        if (settings.activityWeekly()) {
            WeeklySchedule.schedule(tasks, "Weekly activity check", settings.activityDay(), settings.activityTime(),
                    settings.statusZone(), () -> guarded("weekly activity check", this::weeklyActivityCheck), logger);
        }
    }

    private void weeklyActivityCheck() throws Exception {
        ActivityCheck.Outcome outcome = activityCheck.run(jda, "the weekly schedule", false, false);
        if (outcome.notReady() != null) {
            logger.info("[activity] weekly check skipped: " + outcome.notReady());
            var channel = Channels.find(jda, settings.activityLogChannelId(), "activity-check.log-channel-id", logger);
            if (channel != null) {
                channel.sendMessage("Weekly activity check skipped. " + outcome.notReady()).complete();
            }
        }
    }

    @FunctionalInterface
    private interface Task {
        void run() throws Exception;
    }

    /** Scheduled tasks must not throw, or the executor silently stops running them. */
    private void guarded(String name, Task task) {
        if (jda == null) {
            return;
        }
        try {
            task.run();
        } catch (Exception e) {
            logger.log(Level.WARNING, name + " failed", e);
        }
    }

    WelcomePoster welcome() {
        return welcome;
    }

    /** The connected bot, or null while it's still logging in. */
    public JDA jda() {
        return jda;
    }

    public void stop() {
        tasks.shutdownNow();
        statusTimer.shutdownNow();
        listener.shutdown();
        JDA current = jda;
        if (current == null) {
            return;
        }
        current.shutdown();
        try {
            if (!current.awaitShutdown(Duration.ofSeconds(5))) {
                current.shutdownNow();
            }
        } catch (InterruptedException e) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
