package io.github.lrdrandomplayz.smplink.discord;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.utils.FileUpload;
import io.github.lrdrandomplayz.smplink.PluginSettings;
import io.github.lrdrandomplayz.smplink.http.ApiException;
import io.github.lrdrandomplayz.smplink.service.PlayerService;
import io.github.lrdrandomplayz.smplink.service.PlayerService.ActivityEntry;
import io.github.lrdrandomplayz.smplink.service.PlayerService.ActivityReport;
import io.github.lrdrandomplayz.smplink.service.PlayerService.Inventory;
import io.github.lrdrandomplayz.smplink.service.PlayerService.Period;
import io.github.lrdrandomplayz.smplink.service.PlayerService.Ranking;
import io.github.lrdrandomplayz.smplink.service.PlayerService.Standing;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository;
import io.github.lrdrandomplayz.smplink.http.SyncBridge;
import io.github.lrdrandomplayz.smplink.sync.SheetImporter;

/**
 * Handles slash commands, the leaderboard's period menu and page buttons. JDA delivers events on its
 * own threads; the actual work runs on a small pool because it waits on the main thread and the database.
 */
final class BotListener extends ListenerAdapter {

    private static final int PAGE_SIZE = 10;
    // Components carry their owner and state in the custom ID ("leaderboard:<owner>:<period>:<page>",
    // "lbperiod:<owner>"), so only the person who ran /leaderboard can use them and old messages keep
    // working after a restart.
    private static final String BUTTON_PREFIX = "leaderboard:";
    private static final String MENU_PREFIX = "lbperiod:";
    private static final String NOT_LINKED = " not linked to a Minecraft account yet. Run /link in Minecraft to get a code, then use /link here.";

    private final PlayerService players;
    private final SheetImporter importer;
    private final InventoryRenderer renderer;
    private final PluginSettings settings;
    private final ActivityCheck activityCheck;
    private final WhitelistCommand whitelist;
    private final LookupCommand lookup;
    private final WarningsCommand warnings;
    private final TradeBoard trades;
    private final TradeReviews reviews;
    private final Strikes strikes;
    private final ProfileCommand profile;
    private final StrikeCommand strike;
    private JoinLockCommand joinLock;
    // Crash restore cards (null until DiscordBot.setCrashCards).
    private CrashCards crashCards;
    private JoinApplications applications;
    private LinkCommand links;
    private final TrackingRepository tracking;
    private final Logger logger;
    private final RateLimiter commandLimiter = new RateLimiter(5, 60_000);
    // Leaderboard clicks get their own, looser limit so browsing doesn't use up the 5 commands a minute.
    private final RateLimiter clickLimiter = new RateLimiter(20, 60_000);
    private final ExecutorService pool;

    BotListener(PlayerService players, SheetImporter importer, InventoryRenderer renderer, PluginSettings settings,
                ActivityCheck activityCheck, WhitelistCommand whitelist, LookupCommand lookup,
                WarningsCommand warnings, TradeBoard trades, TradeReviews reviews, Strikes strikes,
                ProfileCommand profile, StrikeCommand strike, TrackingRepository tracking, Logger logger) {
        this.strike = strike;
        this.strikes = strikes;
        this.profile = profile;
        this.players = players;
        this.trades = trades;
        this.reviews = reviews;
        this.warnings = warnings;
        this.lookup = lookup;
        this.tracking = tracking;
        this.activityCheck = activityCheck;
        this.whitelist = whitelist;
        this.importer = importer;
        this.renderer = renderer;
        this.settings = settings;
        this.logger = logger;
        AtomicInteger ids = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "SMPLink-Discord-" + ids.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Every slash command the bot registers. */
    static List<CommandData> commands() {
        List<CommandData> commands = new ArrayList<>(List.of(
                ProfileCommand.definition(),
                LinkCommand.definition(),
                LinkCommand.unlinkDefinition(),
                Commands.slash("playtime", "A player's progress towards the weekly activity quota")
                        .addOption(OptionType.USER, "user", "Whose quota to show (defaults to you)", false),
                Commands.slash("leaderboard", "Linked players ranked by playtime (all time, monthly or weekly)"),
                Commands.slash("inv", "Show a linked player's inventory, armor and ender chest (admin only)")
                        .addOption(OptionType.USER, "user", "Whose inventory to show (defaults to you)", false),
                Commands.slash("sync", "Import links from the Google Sheet now (admin only)"),
                WhitelistCommand.definition(),
                LookupCommand.definition(),
                WarningsCommand.definition(),
                StrikeCommand.definition(),
                JoinLockCommand.definition(),
                JoinApplications.definition(),
                Commands.slash("activitycheck", "Weekly playtime check: run it, or pause and resume it (admin only)")
                        .addSubcommands(
                                new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("run",
                                        "Warn members who played too little this week")
                                        .addOption(OptionType.BOOLEAN, "preview", "Only list who would be messaged; send no DMs", false)
                                        .addOption(OptionType.BOOLEAN, "force",
                                                "Check now, even recently added members or with under a week counted", false),
                                new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("pause",
                                        "Stop checks, warnings and strikes")
                                        .addOption(OptionType.STRING, "until", "Resume on this date, e.g. 2026-11-08 (leave out to resume by hand)", false)
                                        .addOption(OptionType.BOOLEAN, "reset_strikes",
                                                "Also cancel open warnings and take back every activity strike", false),
                                new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("resume",
                                        "Turn checks back on; the new season counts from now"))));
        commands.addAll(TradeBoard.definitions());
        return commands;
    }

    void setApplications(JoinApplications applications) {
        this.applications = applications;
    }

    WhitelistCommand whitelist() {
        return whitelist;
    }

    void setCrashCards(CrashCards crashCards) {
        this.crashCards = crashCards;
    }

    void setLinks(LinkCommand links) {
        this.links = links;
    }

    void setJoinLock(JoinLockCommand joinLock) {
        this.joinLock = joinLock;
    }

    void shutdown() {
        pool.shutdownNow();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String name = event.getName();
        long waitMs = commandLimiter.take(event.getUser().getId());
        if (waitMs > 0) {
            event.reply("You're using commands too quickly. Try again in " + ((waitMs + 999) / 1000) + " s.")
                    .setEphemeral(true).queue();
            return;
        }
        if (name.equals("trade")) {
            trades.openForm(event);
            return;
        }
        if (name.equals("link") || name.equals("unlink")) {
            if (links == null) {
                event.reply("Linking with codes is turned off on this server.").setEphemeral(true).queue();
            } else {
                links.onSlash(event, pool);
            }
            return;
        }
        if (name.equals("applications")) {
            if (applications == null) {
                event.reply("Applications aren't running.").setEphemeral(true).queue();
            } else {
                applications.onSlash(event, pool);
            }
            return;
        }
        boolean adminOnly = name.equals("inv") || name.equals("sync") || name.equals("activitycheck")
                || name.equals("strike") || name.equals("joinlock");
        if (adminOnly && !hasRole(event.getMember(), settings.adminRoleId())) {
            event.reply("Only members with the <@&" + settings.adminRoleId() + "> role can use /" + name + ".")
                    .setEphemeral(true).setAllowedMentions(List.of()).queue();
            return;
        }
        // /whitelist and /lookup are also open to the whitelist role, so helpers can manage members without full admin.
        if ((name.equals("whitelist") || name.equals("lookup") || name.equals("warnings")) && !hasRole(event.getMember(), settings.adminRoleId())
                && !hasRole(event.getMember(), settings.whitelistRoleId())) {
            // Logged so a "why can't they use it" report can be checked against the roles Discord actually sent.
            logger.info("[permissions] refused /" + name + " for " + event.getUser().getName() + " (" + event.getUser().getId()
                    + "); their roles: " + (event.getMember() == null ? "unknown (not in a server?)"
                    : event.getMember().getRoles().stream().map(r -> r.getName() + " " + r.getId()).toList())
                    + "; allowed: " + settings.whitelistRoleId() + ", " + settings.adminRoleId());
            event.reply("Only members with the <@&" + settings.whitelistRoleId() + "> or <@&" + settings.adminRoleId()
                            + "> role can use /" + name + ".")
                    .setEphemeral(true).setAllowedMentions(List.of()).queue();
            return;
        }

        User target = event.getOption("user", event.getUser(), OptionMapping::getAsUser);
        boolean self = target.getId().equals(event.getUser().getId());
        boolean ephemeral = name.equals("sync") || name.equals("activitycheck") || name.equals("whitelist")
                || name.equals("lookup") || name.equals("warnings") || name.equals("trades") || name.equals("strike") || name.equals("joinlock");
        boolean preview = event.getOption("preview", false, OptionMapping::getAsBoolean);
        boolean force = event.getOption("force", false, OptionMapping::getAsBoolean);
        event.deferReply(ephemeral).queue(hook -> pool.execute(() -> {
            try {
                switch (name) {
                    case "profile" -> showProfile(hook, event.getUser().getId(), target, ProfileCommand.Page.STATS);
                    case "trades" -> trades.search(hook, event.getOption("item", OptionMapping::getAsString),
                            event.getOption("user", OptionMapping::getAsUser));
                    case "playtime" -> {
                        Standing standing = players.playtime(target.getId());
                        String exemptAs = standing.original() && activityCheck.minSeconds(true) <= 0 ? "Original member"
                                : strikes.exemptRoleName(event.getJDA(), standing.discordId());
                        hook.editOriginalEmbeds(Embeds.quota(target.getName(), standing,
                                exemptAs == null ? quotaLine(standing, event.getJDA()) : null,
                                Embeds.formatHours(activityCheck.minSeconds(standing.original())), exemptAs)).queue();
                    }
                    case "warnings" -> warnings.handle(hook);
                    case "strike" -> {
                        if ("add".equals(event.getSubcommandName())) {
                            strike.add(hook, event.getUser(), target, event.getOption("reason", OptionMapping::getAsString));
                        } else {
                            strike.remove(hook, event.getJDA(), event.getUser(), target, event.getOption("id", 0L, OptionMapping::getAsLong));
                        }
                    }
                    case "joinlock" -> {
                        if (joinLock == null) {
                            hook.editOriginal("The join lock isn't running.").queue();
                        } else {
                            joinLock.handle(hook, event.getUser(), event.getSubcommandName(),
                                    event.getOption("when", OptionMapping::getAsString), event.getOption("message", OptionMapping::getAsString));
                        }
                    }
                    case "lookup" -> lookup.handle(hook, event.getOption("discord", OptionMapping::getAsUser),
                            event.getOption("ign", OptionMapping::getAsString));
                    case "leaderboard" -> showPage(hook, event.getUser().getId(), Period.ALL_TIME, 0);
                    case "inv" -> showInventory(hook, target);
                    case "sync" -> runSync(hook, event.getUser());
                    case "activitycheck" -> {
                        switch (event.getSubcommandName() == null ? "run" : event.getSubcommandName()) {
                            case "pause" -> pauseActivityCheck(hook, event.getUser(), event.getOption("until", OptionMapping::getAsString),
                                    event.getOption("reset_strikes", false, OptionMapping::getAsBoolean));
                            case "resume" -> resumeActivityCheck(hook, event.getUser());
                            default -> runActivityCheck(hook, event.getJDA(), event.getUser(), preview, force);
                        }
                    }
                    case "whitelist" -> {
                        User member = event.getOption("discord", OptionMapping::getAsUser);
                        String ign = event.getOption("ign", OptionMapping::getAsString);
                        if ("add".equals(event.getSubcommandName())) {
                            whitelist.add(hook, event.getUser(), member, event.getOption("version", OptionMapping::getAsString), ign,
                                    event.getOption("donor", false, OptionMapping::getAsBoolean));
                        } else {
                            whitelist.remove(hook, event.getUser(), member, ign);
                        }
                    }
                    default -> hook.editOriginal("Unknown command.").queue();
                }
            } catch (Exception e) {
                hook.editOriginal(describe(e, target, self, "/" + name + " by " + event.getUser().getId())).setEmbeds().queue();
            }
        }));
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (JoinApplications.isComponent(id)) {
            if (applications == null) {
                event.reply("Applications are closed right now.").setEphemeral(true).queue();
            } else {
                applications.onButton(event, pool);
            }
            return;
        }
        if (WhitelistCommand.isButton(id)) {
            whitelist.onButton(event, pool);
            return;
        }
        if (CrashCards.isComponent(id)) {
            if (crashCards == null) {
                event.reply("Crash recovery isn't running.").setEphemeral(true).queue();
            } else {
                crashCards.onButton(event, pool);
            }
            return;
        }
        if (TradeBoard.isStepButton(id)) {
            trades.onStepButton(event);
            return;
        }
        if (TradeBoard.isComponent(id)) {
            reviews.onButton(event, pool);
            return;
        }
        if (!id.startsWith(BUTTON_PREFIX)) {
            return;
        }
        String[] parts = id.substring(BUTTON_PREFIX.length()).split(":");
        String owner;
        Period period;
        String rawPage;
        if (parts.length == 3) {
            owner = parts[0];
            period = Period.fromId(parts[1]);
            rawPage = parts[2];
        } else if (parts.length == 2) {
            // Buttons from before the period menu existed were always all-time.
            owner = parts[0];
            period = Period.ALL_TIME;
            rawPage = parts[1];
        } else {
            event.reply("This leaderboard is out of date. Run /leaderboard to get your own.").setEphemeral(true).queue();
            return;
        }
        int page;
        try {
            page = Math.max(0, Integer.parseInt(rawPage));
        } catch (NumberFormatException e) {
            page = 0;
        }
        if (!allowedToUse(event.getUser().getId(), owner, event)) {
            return;
        }
        int target = page;
        event.deferEdit().queue(hook -> pool.execute(() -> refreshPage(hook, owner, period, target, event.getUser())));
    }

    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        if (TradeReviews.isPartnerMenu(event.getComponentId())) {
            reviews.onPartnerPicked(event, pool);
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if (event.getName().equals("trade") || event.getName().equals("trades")) {
            trades.onAutoComplete(event);
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (TradeBoard.isForm(event.getModalId())) {
            trades.onForm(event, pool);
        } else if (JoinApplications.isForm(event.getModalId()) && applications != null) {
            applications.onForm(event, pool);
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        String id = event.getComponentId();
        if (id.startsWith(ProfileCommand.MENU_PREFIX)) {
            onProfileMenu(event);
            return;
        }
        if (TradeReviews.isPickMenu(id)) {
            reviews.onInterestedPicked(event, pool);
            return;
        }
        if (!id.startsWith(MENU_PREFIX)) {
            return;
        }
        String owner = id.substring(MENU_PREFIX.length());
        if (!allowedToUse(event.getUser().getId(), owner, event)) {
            return;
        }
        Period period = Period.fromId(event.getValues().isEmpty() ? "all" : event.getValues().get(0));
        // A new period starts from its first page.
        event.deferEdit().queue(hook -> pool.execute(() -> refreshPage(hook, owner, period, 0, event.getUser())));
    }

    /** Only the person who ran /leaderboard can change it; everyone else gets a private note. */
    private boolean allowedToUse(String clicker,
                                 String owner,
                                 net.dv8tion.jda.api.interactions.callbacks.IReplyCallback event) {
        if (!owner.equals(clicker)) {
            event.reply("Only <@" + owner + "> can change this leaderboard. Run /leaderboard to get your own.")
                    .setEphemeral(true).setAllowedMentions(List.of()).queue();
            return false;
        }
        long waitMs = clickLimiter.take(clicker);
        if (waitMs > 0) {
            event.reply("You're changing the leaderboard too quickly. Try again in " + ((waitMs + 999) / 1000) + " s.")
                    .setEphemeral(true).queue();
            return false;
        }
        return true;
    }

    /** Switches a /profile card to another page; only the person who ran /profile can. */
    private void onProfileMenu(StringSelectInteractionEvent event) {
        String[] parts = event.getComponentId().substring(ProfileCommand.MENU_PREFIX.length()).split(":");
        if (parts.length != 2) {
            return;
        }
        String owner = parts[0];
        if (!owner.equals(event.getUser().getId())) {
            event.reply("Only <@" + owner + "> can change this profile. Run /profile to get your own.")
                    .setEphemeral(true).setAllowedMentions(List.of()).queue();
            return;
        }
        long waitMs = clickLimiter.take(owner);
        if (waitMs > 0) {
            event.reply("You're switching pages too quickly. Try again in " + ((waitMs + 999) / 1000) + " s.")
                    .setEphemeral(true).queue();
            return;
        }
        ProfileCommand.Page page = ProfileCommand.Page.fromId(event.getValues().isEmpty() ? "stats" : event.getValues().get(0));
        event.deferEdit().queue(hook -> pool.execute(() -> {
            try {
                User target = event.getJDA().retrieveUserById(parts[1]).complete();
                showProfile(hook, owner, target, page);
            } catch (Exception e) {
                hook.sendMessage(describe(e, null, false, "profile page by " + owner)).setEphemeral(true).queue();
            }
        }));
    }

    private void showProfile(InteractionHook hook, String viewerId, User target, ProfileCommand.Page page) throws Exception {
        ProfileCommand.Rendered rendered = profile.render(viewerId, target, page);
        // The whole message is replaced with the card, so an older embed-style profile is converted too.
        var edit = hook.editOriginalComponents(rendered.card()).useComponentsV2().setReplace(true);
        // Replacing the attachments means switching to a page without a banner drops the old one.
        edit.setFiles(rendered.files()).queue();
    }

    private void refreshPage(InteractionHook hook, String owner, Period period, int page, User clicker) {
        try {
            showPage(hook, owner, period, page);
        } catch (Exception e) {
            hook.sendMessage(describe(e, null, false, "leaderboard change by " + clicker.getId())).setEphemeral(true).queue();
        }
    }

    private void showPage(InteractionHook hook, String ownerId, Period period, int page) throws Exception {
        Ranking board = players.leaderboard(period, PAGE_SIZE, page * PAGE_SIZE);
        int pageCount = Math.max(1, (board.totalRanked() + PAGE_SIZE - 1) / PAGE_SIZE);
        // The list can shrink between clicks (links removed from the sheet), so clamp to the last real page.
        if (page >= pageCount) {
            page = pageCount - 1;
            board = players.leaderboard(period, PAGE_SIZE, page * PAGE_SIZE);
        }

        StringSelectMenu.Builder menu = StringSelectMenu.create(MENU_PREFIX + ownerId).setPlaceholder("Time period");
        for (Period option : Period.values()) {
            menu.addOptions(SelectOption.of(option.label, option.id)
                    .withDescription(option == Period.ALL_TIME ? "Total playtime" : option == Period.WEEKLY
                            ? "Since " + settings.recapDay().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + " " + settings.recapTime() : "Last " + option.days + " days"));
        }
        menu.setDefaultValues(period.id);

        String pagePrefix = BUTTON_PREFIX + ownerId + ":" + period.id + ":";
        ActionRow buttons = ActionRow.of(
                Button.secondary(pagePrefix + (page - 1), Emoji.fromUnicode("◀️")).withDisabled(page == 0),
                Button.secondary(pagePrefix + (page + 1), Emoji.fromUnicode("▶️")).withDisabled(page >= pageCount - 1));
        hook.editOriginalEmbeds(Embeds.leaderboard(board, page, pageCount, id -> ""))
                .setComponents(ActionRow.of(menu.build()), buttons)
                .queue();
    }

    private void showInventory(InteractionHook hook, User target) throws Exception {
        Inventory inv = players.inventory(target.getId());
        byte[] image = null;
        try {
            image = renderer.render(new InventoryRenderer.Contents(inv.name(), inv.inventory(), inv.armor(), inv.enderChest()));
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not draw the inventory of " + inv.name() + ", sending text instead", e);
        }
        if (image != null) {
            hook.editOriginalEmbeds(Embeds.inventory(target.getName(), inv, "inventory.png"))
                    .setFiles(FileUpload.fromData(image, "inventory.png")).queue();
        } else {
            hook.editOriginalEmbeds(Embeds.inventory(target.getName(), inv, null)).queue();
        }
    }

    private void runSync(InteractionHook hook, User requester) {
        if (importer == null) {
            hook.editOriginal("Sheet sync is turned off. Set sheet.id in plugins/SMPLink/config.yml.").queue();
            return;
        }
        logger.info("[sync] requested by " + requester.getName() + " (" + requester.getId() + ")");
        // The command only starts or joins the background import; Sheets and Mojang are called from there.
        importer.run("/sync by " + requester.getName()).whenComplete((report, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                logger.warning("[sync] import failed: " + cause.getMessage());
                hook.editOriginal("Import failed: " + cause.getMessage()).queue();
            } else {
                hook.editOriginalEmbeds(Embeds.syncReport(report)).queue();
            }
        });
    }

    private void pauseActivityCheck(InteractionHook hook, User admin, String until, boolean resetStrikes) throws Exception {
        ActivityPause pause = activityCheck.pause();
        long untilMs = 0;
        if (until != null && !until.isBlank()) {
            try {
                untilMs = pause.startOfDay(java.time.LocalDate.parse(until.trim()));
            } catch (java.time.format.DateTimeParseException e) {
                hook.editOriginal("Couldn't read **" + until + "**. Write the date like `2026-11-08`.").queue();
                return;
            }
            if (untilMs <= System.currentTimeMillis()) {
                hook.editOriginal("That date has already passed.").queue();
                return;
            }
        }
        String reset = "";
        if (resetStrikes) {
            ActivityPause.Reset done = pause.resetSeason(admin.getName());
            reset = "\n" + Glyph.UNDO + " Cancelled **" + done.warnings() + "** open warning(s) and took back **" + done.strikes()
                    + "** activity strike(s). Raid and manual strikes weren't touched.";
        }
        pause.pause(untilMs, admin.getName());
        logger.info("[activity] " + admin.getName() + " paused activity checks" + (untilMs > 0 ? " until " + until.trim() : "")
                + (resetStrikes ? " and reset strikes" : ""));
        hook.editOriginal(Glyph.TIME + " Activity checks are **paused**" + (untilMs > 0 ? " until <t:" + untilMs / 1000 + ":F>" : "")
                + ". Nobody is checked, warned or struck meanwhile." + reset
                + "\nWhen they resume" + (untilMs > 0 ? "" : " (`/activitycheck resume`)") + ", a new season starts and the first "
                + "check waits until it has a full week of playtime.").queue();
    }

    private void resumeActivityCheck(InteractionHook hook, User admin) {
        ActivityPause pause = activityCheck.pause();
        if (!pause.isPaused()) {
            hook.editOriginal("Activity checks aren't paused.").queue();
            return;
        }
        pause.resume(admin.getName());
        long firstCheck = System.currentTimeMillis() + ActivityCheck.WINDOW_DAYS * ActivityCheck.DAY_MS;
        hook.editOriginal(Glyph.YES + " Activity checks are **back on**. The new season counts from now, so the first check that "
                + "can warn anyone runs on the first weekly check after <t:" + firstCheck / 1000 + ":F>.").queue();
    }

    private void runActivityCheck(InteractionHook hook, JDA jda, User admin, boolean preview, boolean force)
            throws Exception {
        ActivityCheck.Outcome outcome = activityCheck.run(jda, admin.getName(), preview, force);
        if (outcome.notReady() != null) {
            hook.editOriginal(outcome.notReady() + " Use `preview:true` to look now, or `force:true` to send DMs anyway.")
                    .queue();
            return;
        }
        hook.editOriginalEmbeds(Embeds.activitySummary(outcome.report(), outcome.lines(), preview, outcome.failures()))
                .queue();
    }

    /** The weekly quota for a checked member; with an open warning, their progress towards the deadline. */
    private String quotaLine(Standing standing, JDA jda) throws Exception {
        long minSeconds = activityCheck.minSeconds(standing.original());
        ActivityPause pause = activityCheck.pause();
        if (pause.isPaused()) {
            return Glyph.TIME + " Activity checks are paused" + (pause.until() > 0 ? " until <t:" + pause.until() / 1000 + ":F>" : "")
                    + ". Nobody is checked or struck until then; the weekly quota is " + Embeds.formatHours(minSeconds) + ".";
        }
        String required = Embeds.formatHours(minSeconds);
        String strikeLine = "\n" + strikes.line(strikes.active(standing.discordId()), strikes.limit(jda, standing.discordId()));
        var warning = SyncBridge.await(tracking.pendingWarning(standing.discordId()), PlayerService.DB_TIMEOUT_MS);
        if (warning.isPresent()) {
            TrackingRepository.Warning open = warning.get();
            long played = players.secondsPlayedBetween(standing.uuid(), open.windowStart(),
                    Math.min(System.currentTimeMillis(), open.deadline()));
            long left = Math.max(0, minSeconds - played);
            return Embeds.quotaBar(played, minSeconds, 16) + "\n"
                    + "**" + Embeds.formatPlaytime(played) + "** of " + required + " · "
                    + (left == 0 ? Glyph.YES + " reached, confirmation coming shortly" : Glyph.WARN + " **" + Embeds.formatPlaytime(left) + " left**")
                    + "\n" + Glyph.TIME + " Deadline <t:" + open.deadline() / 1000 + ":F> (<t:" + open.deadline() / 1000 + ":R>)"
                    + "\nCounting since <t:" + open.windowStart() / 1000 + ":f>" + strikeLine;
        }
        long week = standing.weekSeconds();
        return Embeds.quotaBar(week, minSeconds, 16) + "\n"
                + "**" + Embeds.formatPlaytime(week) + "** of " + required + " since <t:"
                + Embeds.windowStart(standing.trackingStarted(), 7) / 1000 + ":f>"
                + (week >= minSeconds ? " · " + Glyph.YES + " on track" : " · " + Embeds.formatPlaytime(minSeconds - week) + " to go")
                + strikeLine;
    }

    static boolean hasRole(Member member, String roleId) {
        return member != null && !roleId.isEmpty()
                && member.getRoles().stream().anyMatch(role -> role.getId().equals(roleId));
    }

    /** Turns a failure into a message a player can act on, logging anything unexpected. */
    private String describe(Exception e, User target, boolean self, String context) {
        if (e instanceof ApiException api) {
            if (api.code().equals("not_linked") && target != null) {
                return (self ? "Your Discord account is" : "**" + target.getName() + "** is") + NOT_LINKED;
            }
            return api.getMessage();
        }
        if (e instanceof TimeoutException) {
            return "The Minecraft server is busy and did not answer in time. Try again in a moment.";
        }
        logger.log(Level.WARNING, context + " failed", e);
        return "Something went wrong. The error has been logged in the server console.";
    }
}
