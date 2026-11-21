package io.github.lrdrandomplayz.smplink.discord;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Message.MentionType;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.modals.Modal;
import io.github.lrdrandomplayz.smplink.PluginSettings;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.RemovedMember;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.Strike;
import io.github.lrdrandomplayz.smplink.http.SyncBridge;
import io.github.lrdrandomplayz.smplink.service.PlayerService;

/**
 * Join applications without ticket channels. /applications panel posts an Apply button; it opens a form, and the
 * answers go to the review channel as a card (Components V2: dividers, skin thumbnail, buttons inside) with Accept,
 * Deny and Ask buttons. Accept runs /whitelist add (sheet, link, whitelist, role and welcome DM), Deny DMs the reason,
 * Ask opens a private thread with the applicant in the panel's channel. Players removed by the activity check may
 * apply again; their card says so, and accepting them clears the removal and their old strikes.
 * <p>
 * kv keys: {@code app.pending.<discord>} (card message ID), {@code app.denied.<discord>} (epoch ms),
 * {@code app.card.<discord>} (the card's data as JSON, so it can be redrawn) and {@code app.panel-channel}.
 */
final class JoinApplications {

    /** applications.* from config.yml. "off" or empty IDs turn a part off. */
    record Settings(String reviewChannelId, String staffRoleId, String rulesChannelId, String rulesMessageId,
                    String rulesEmoji, int reapplyDays) {

        Settings {
            reviewChannelId = off(reviewChannelId);
            staffRoleId = off(staffRoleId);
            rulesChannelId = off(rulesChannelId);
            rulesMessageId = off(rulesMessageId);
            rulesEmoji = rulesEmoji == null || rulesEmoji.isBlank() ? "✅" : rulesEmoji.trim();
            reapplyDays = Math.max(0, reapplyDays);
        }

        private static String off(String value) {
            return value == null || value.isBlank() || value.trim().equalsIgnoreCase("off") ? "" : value.trim();
        }

        boolean enabled() {
            return !reviewChannelId.isEmpty();
        }
    }

    /** What a card shows. Kept as JSON in the kv table so Accept, Deny and Ask can redraw it. */
    static final class Card {
        String discordId;
        String ign;
        String edition;
        String hours;
        List<String> style = new ArrayList<>();
        boolean agrees;
        String rules = "";
        long accountMade;
        long joined;
        String history = "";
        boolean bannedBefore;
        String question = "";
        String state = "pending";   // pending, accepted, denied
        String decision = "";       // "<@staff> <t:..:R>"
        String reason = "";
    }

    private static final String PREFIX = "app:";
    private static final String APPLY = PREFIX + "apply";
    private static final String ACCEPT = PREFIX + "acc:";   // app:acc:<discord>
    private static final String DENY = PREFIX + "deny:";    // app:deny:<discord>
    private static final String ASK = PREFIX + "ask:";      // app:ask:<discord>
    private static final String FORM = PREFIX + "form";
    private static final String DENY_FORM = PREFIX + "denyform:"; // + <discord>
    private static final String PANEL_CHANNEL_KEY = "app.panel-channel";
    private static final Map<String, Glyph> STYLES = Map.of("Grind", Glyph.MINING, "Build", Glyph.CRAFT,
            "Fight", Glyph.KILLS, "Explore", Glyph.LOCATION);
    private static final Gson GSON = new Gson();

    private final Settings config;
    private final PluginSettings settings;
    private final PlayerService players;
    private final TrackingRepository tracking;
    private final WhitelistCommand whitelist;
    private final Logger logger;

    JoinApplications(Settings config, PluginSettings settings, PlayerService players, TrackingRepository tracking,
                     WhitelistCommand whitelist, Logger logger) {
        this.config = config;
        this.settings = settings;
        this.players = players;
        this.tracking = tracking;
        this.whitelist = whitelist;
        this.logger = logger;
    }

    static SlashCommandData definition() {
        return Commands.slash("applications", "Join applications (admin only)")
                .addSubcommands(new SubcommandData("panel", "Post the Apply to join button in this channel"));
    }

    static boolean isComponent(String id) {
        return id.startsWith(PREFIX);
    }

    static boolean isForm(String id) {
        return id.equals(FORM) || id.startsWith(DENY_FORM);
    }

    private boolean staff(Member member) {
        return BotListener.hasRole(member, settings.adminRoleId()) || BotListener.hasRole(member, settings.whitelistRoleId())
                || (!config.staffRoleId().isEmpty() && BotListener.hasRole(member, config.staffRoleId()));
    }

    private String quota() {
        return Embeds.formatHours(Math.round(settings.activityMinHours() * 3600));
    }

    // ---------------------------------------------------------------- the panel

    void onSlash(SlashCommandInteractionEvent event, ExecutorService pool) {
        if (!BotListener.hasRole(event.getMember(), settings.adminRoleId())) {
            event.reply("Only members with the <@&" + settings.adminRoleId() + "> role can use /applications.")
                    .setEphemeral(true).setAllowedMentions(List.of()).queue();
            return;
        }
        if (!config.enabled()) {
            event.reply("Set `applications.review-channel-id` in SMPLink's config.yml first (then restart).")
                    .setEphemeral(true).queue();
            return;
        }
        String rules = config.rulesChannelId().isEmpty() ? "the rules" : "the rules in <#" + config.rulesChannelId() + ">";
        Container panel = Container.of(
                TextDisplay.of("# " + Glyph.WELCOME + " Join " + io.github.lrdrandomplayz.smplink.Branding.name() + (io.github.lrdrandomplayz.smplink.Branding.tagline().isEmpty() ? "" : "\n-# " + io.github.lrdrandomplayz.smplink.Branding.tagline())),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of("### How it works\n"
                        + Glyph.NOTE + " **Apply** with the button below. It takes a minute.\n"
                        + Glyph.SEARCH + " **Staff review** every application.\n"
                        + Glyph.YES + " **You get a DM** with the result, and the IP if you're in."),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of("### Before you apply\n"
                        + Glyph.BOOK + " Read " + rules + (config.rulesMessageId().isEmpty() ? "." : " and react " + config.rulesEmoji() + " to them.")
                        + "\n" + Glyph.TIME + " New members play at least **" + quota() + " a week**."),
                Separator.createDivider(Separator.Spacing.SMALL),
                ActionRow.of(Button.success(APPLY, "Apply to join").withEmoji(Glyph.EDIT.emoji()))
        ).withAccentColor(Embeds.BRAND);
        event.getChannel().sendMessageComponents(panel).useComponentsV2()
                .queue(sent -> pool.execute(() -> setValue(PANEL_CHANNEL_KEY, event.getChannel().getId())));
        event.reply("Posted. Applications go to <#" + config.reviewChannelId() + ">.").setEphemeral(true).queue();
    }

    // ---------------------------------------------------------------- buttons

    void onButton(ButtonInteractionEvent event, ExecutorService pool) {
        String id = event.getComponentId();
        if (id.equals(APPLY)) {
            apply(event);
            return;
        }
        if (!staff(event.getMember())) {
            event.reply("Only staff can decide applications.").setEphemeral(true).queue();
            return;
        }
        if (id.startsWith(ACCEPT)) {
            String discordId = id.substring(ACCEPT.length()).split(":")[0];
            event.deferReply(true).queue(hook -> pool.execute(() -> accept(event, hook, discordId)));
        } else if (id.startsWith(DENY)) {
            String discordId = id.substring(DENY.length()).split(":")[0];
            event.replyModal(Modal.create(DENY_FORM + discordId, "Deny this application")
                    .addComponents(Label.of("Reason (the applicant sees this)", TextInput.create("reason", TextInputStyle.PARAGRAPH)
                            .setRequired(true).setMaxLength(500).build()))
                    .build()).queue();
        } else if (id.startsWith(ASK)) {
            String discordId = id.substring(ASK.length()).split(":")[0];
            event.deferReply(true).queue(hook -> pool.execute(() -> ask(event, hook, discordId)));
        }
    }

    /** Checks they may apply, then shows the form. Must answer within 3 seconds, so the checks are quick lookups. */
    private void apply(ButtonInteractionEvent event) {
        String discordId = event.getUser().getId();
        String refusal;
        try {
            refusal = refusal(discordId);
        } catch (Exception e) {
            logger.log(Level.WARNING, "[applications] couldn't check " + discordId, e);
            refusal = "Something went wrong. Try again in a moment.";
        }
        if (refusal != null) {
            event.reply(refusal).setEphemeral(true).queue();
            return;
        }
        event.replyModal(Modal.create(FORM, ("Apply to " + io.github.lrdrandomplayz.smplink.Branding.name()).length() > 45 ? "Apply to join" : "Apply to " + io.github.lrdrandomplayz.smplink.Branding.name()).addComponents(
                Label.of("Minecraft username", TextInput.create("ign", TextInputStyle.SHORT)
                        .setRequiredRange(3, 16).setPlaceholder("Exactly as it is in game").build()),
                Label.of("Premium or cracked?", StringSelectMenu.create("edition")
                        .addOptions(SelectOption.of("Premium (bought Minecraft)", "premium"), SelectOption.of("Cracked", "cracked"))
                        .build()),
                Label.of("How much can you play per day?", TextInput.create("hours", TextInputStyle.SHORT)
                        .setRequiredRange(1, 40).setPlaceholder("e.g. 2 hours on weekdays, more on weekends").build()),
                Label.of("What will you do on the SMP?", StringSelectMenu.create("style")
                        .addOptions(STYLES.entrySet().stream().sorted(Map.Entry.comparingByKey())
                                .map(e -> SelectOption.of(e.getKey(), e.getKey()).withEmoji(e.getValue().emoji())).toList())
                        .setRequiredRange(1, 4).build()),
                Label.of("Rules and playtime", StringSelectMenu.create("agree")
                        .addOptions(SelectOption.of("I've read the rules and can play " + quota() + " a week", "yes"),
                                SelectOption.of("Not sure I can play " + quota() + " a week", "unsure"))
                        .build())
        ).build()).queue();
    }

    /** Why this person can't apply right now, or null if they can. Earlier activity-check removals don't stop them. */
    private String refusal(String discordId) throws Exception {
        if (!config.enabled()) {
            return "Applications are closed right now.";
        }
        if (players.links().stream().anyMatch(link -> discordId.equals(link.discordId()))) {
            return "You're already a member of " + io.github.lrdrandomplayz.smplink.Branding.name() + ".";
        }
        if (!value("app.pending." + discordId).isEmpty()) {
            return "Your application is waiting for staff. You'll get a DM when it's decided.";
        }
        String denied = value("app.denied." + discordId);
        if (!denied.isEmpty()) {
            long again = Long.parseLong(denied) + config.reapplyDays() * 86_400_000L;
            if (System.currentTimeMillis() < again) {
                return "You can apply again <t:" + again / 1000 + ":R>.";
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- forms

    void onForm(ModalInteractionEvent event, ExecutorService pool) {
        if (event.getModalId().equals(FORM)) {
            event.deferReply(true).queue(hook -> pool.execute(() -> submit(event, hook)));
        } else if (event.getModalId().startsWith(DENY_FORM)) {
            String discordId = event.getModalId().substring(DENY_FORM.length());
            String reason = event.getValue("reason").getAsString().trim();
            Message message = event.getMessage();
            event.deferReply(true).queue(hook -> pool.execute(() -> deny(event.getJDA(), hook, event.getUser(), message, discordId, reason)));
        }
    }

    private void submit(ModalInteractionEvent event, InteractionHook hook) {
        User applicant = event.getUser();
        Card card = new Card();
        card.discordId = applicant.getId();
        card.ign = event.getValue("ign").getAsString().trim();
        if (!card.ign.matches("[A-Za-z0-9_]{3,16}")) {
            hook.editOriginal("`" + card.ign + "` isn't a valid Minecraft username (3 to 16 letters, numbers or _). Press Apply again.")
                    .queue();
            return;
        }
        card.edition = event.getValue("edition").getAsStringList().get(0);
        card.hours = event.getValue("hours").getAsString().trim().replace("\n", " ");
        card.style = new ArrayList<>(event.getValue("style").getAsStringList());
        card.agrees = event.getValue("agree").getAsStringList().get(0).equals("yes");
        card.accountMade = applicant.getTimeCreated().toEpochSecond();
        card.joined = event.getMember() == null ? 0 : event.getMember().getTimeJoined().toEpochSecond();
        try {
            String refusal = refusal(applicant.getId());
            if (refusal != null) {
                hook.editOriginal(refusal).queue();
                return;
            }
            TextChannel review = event.getJDA().getTextChannelById(config.reviewChannelId());
            if (review == null) {
                hook.editOriginal("Applications aren't set up properly. Please tell staff.").queue();
                logger.warning("[applications] review channel " + config.reviewChannelId() + " not found");
                return;
            }
            card.rules = rulesReaction(event.getJDA(), applicant.getId());
            history(card);
            List<MessageTopLevelComponent> parts = new ArrayList<>();
            if (!config.staffRoleId().isEmpty()) {
                parts.add(TextDisplay.of("<@&" + config.staffRoleId() + "> new application"));
            }
            parts.add(render(card));
            Message sent = review.sendMessageComponents(parts).useComponentsV2()
                    .setAllowedMentions(EnumSet.of(MentionType.ROLE)).complete();
            setValue("app.card." + applicant.getId(), GSON.toJson(card));
            setValue("app.pending." + applicant.getId(), sent.getId());
            logger.info("[applications] " + applicant.getName() + " (" + applicant.getId() + ") applied as " + card.ign);
            hook.editOriginal(Glyph.YES + " Application sent! Staff will look at it soon and you'll get a DM with the result. "
                    + "Make sure your DMs from this server are open.").queue();
        } catch (Exception e) {
            logger.log(Level.WARNING, "[applications] couldn't post the application from " + applicant.getId(), e);
            hook.editOriginal("Couldn't send your application. Try again in a moment.").queue();
        }
    }

    /** Whether they reacted to the rules message, as words for the card ("" when not checked). */
    private String rulesReaction(JDA jda, String discordId) {
        if (config.rulesChannelId().isEmpty() || config.rulesMessageId().isEmpty()) {
            return "";
        }
        try {
            TextChannel channel = jda.getTextChannelById(config.rulesChannelId());
            if (channel == null) {
                return "rules reaction unknown";
            }
            boolean reacted = channel.retrieveReactionUsersById(config.rulesMessageId(), Emoji.fromFormatted(config.rulesEmoji()))
                    .complete().stream().anyMatch(user -> user.getId().equals(discordId));
            return reacted ? "reacted to the rules" : Glyph.WARN + " hasn't reacted to the rules";
        } catch (RuntimeException e) {
            return "couldn't check the rules reaction";
        }
    }

    /** Fills in the history line: an earlier activity-check removal (with the strikes that led to it) or deny. */
    private void history(Card card) {
        try {
            Optional<RemovedMember> removed = SyncBridge.await(tracking.findRemoval(card.discordId, card.ign), PlayerService.DB_TIMEOUT_MS);
            if (removed.isPresent()) {
                card.bannedBefore = true;
                RemovedMember r = removed.get();
                List<String> reasons = SyncBridge.await(tracking.strikesSince(r.discordId(), 0), PlayerService.DB_TIMEOUT_MS).stream()
                        .filter(s -> s.givenAt() <= r.removedAt()).map(Strike::reason).filter(s -> s != null && !s.isBlank()).toList();
                String why = reasons.isEmpty() ? "missing the weekly playtime"
                        : String.join("; ", reasons.subList(Math.max(0, reasons.size() - 3), reasons.size()));
                card.history = Glyph.ALERT + " **Was banned before** <t:" + r.removedAt() / 1000 + ":D> as `" + r.ign() + "` for: " + why
                        + "\n-# Accepting gives them a fresh start: the old removal and strikes are cleared.";
                return;
            }
            String denied = value("app.denied." + card.discordId);
            card.history = denied.isEmpty() ? "first application" : "denied before, <t:" + Long.parseLong(denied) / 1000 + ":D>";
        } catch (Exception e) {
            card.history = "couldn't check";
        }
    }

    // ---------------------------------------------------------------- the card

    static Container render(Card c) {
        boolean pending = c.state.equals("pending");
        String head = switch (c.state) {
            case "accepted" -> "## " + Glyph.YES + " " + c.ign + " accepted";
            case "denied" -> "## " + Glyph.NO + " " + c.ign + " denied";
            default -> "## " + Glyph.PLAYER + " " + c.ign;
        };
        String sub = "-# " + (pending ? (c.bannedBefore ? "Returning player · " : "New application · ") : "")
                + (c.edition.equals("cracked") ? "Cracked" : "Premium") + " · <@" + c.discordId + ">";
        List<ContainerChildComponent> parts = new ArrayList<>();
        parts.add(Section.of(Thumbnail.fromUrl("https://mc-heads.net/avatar/" + c.ign + "/128"), TextDisplay.of(head + "\n" + sub)));
        parts.add(Separator.createDivider(Separator.Spacing.SMALL));

        StringBuilder style = new StringBuilder();
        for (String s : c.style) {
            Glyph g = STYLES.get(s);
            if (g != null) {
                style.append(g).append(' ');
            }
        }
        parts.add(TextDisplay.of(Glyph.TIME + " **Plays** " + c.hours + " a day\n"
                + style + "**Wants to** " + String.join(", ", c.style) + "\n"
                + Glyph.BOOK + " **Rules and weekly playtime** " + (c.agrees ? Glyph.YES + " agreed" : Glyph.WARN + " not sure")
                + (c.rules.isEmpty() ? "" : ", " + c.rules)));
        parts.add(Separator.createDivider(Separator.Spacing.SMALL));

        String lines = Glyph.CHAT + " **Discord** made <t:" + c.accountMade + ":R>"
                + (c.joined > 0 ? ", joined <t:" + c.joined + ":R>" : "") + "\n"
                + (c.bannedBefore ? c.history : Glyph.NEW + " **History** " + c.history)
                + (c.question.isEmpty() ? "" : "\n" + Glyph.QUESTION + " **Question** " + c.question);
        parts.add(TextDisplay.of(lines));
        parts.add(Separator.createDivider(Separator.Spacing.SMALL));

        if (pending) {
            parts.add(ActionRow.of(
                    Button.success(ACCEPT + c.discordId, "Accept").withEmoji(Glyph.YES.emoji()),
                    Button.danger(DENY + c.discordId, "Deny").withEmoji(Glyph.NO.emoji()),
                    Button.secondary(ASK + c.discordId, "Ask a question").withEmoji(Glyph.QUESTION.emoji())));
        } else if (c.state.equals("accepted")) {
            parts.add(TextDisplay.of(Glyph.SHIELD + " Accepted by " + c.decision + ". Added to the sheet, linked and whitelisted."));
        } else {
            parts.add(TextDisplay.of(Glyph.SHIELD + " Denied by " + c.decision + "\n> " + c.reason.replace("\n", "\n> ")));
        }
        int colour = switch (c.state) {
            case "accepted" -> Embeds.SUCCESS;
            case "denied" -> Embeds.DANGER;
            default -> c.bannedBefore ? Embeds.WARNING : Embeds.BRAND;
        };
        return Container.of(parts).withAccentColor(colour);
    }

    private Card load(String discordId) {
        String json = value("app.card." + discordId);
        return json.isEmpty() ? null : GSON.fromJson(json, Card.class);
    }

    /** Saves the card and redraws it (without the staff ping). */
    private void redraw(Message message, Card card) {
        setValue("app.card." + card.discordId, GSON.toJson(card));
        if (message != null) {
            message.editMessageComponents(render(card)).useComponentsV2().setAllowedMentions(List.of()).queue(null,
                    e -> logger.warning("[applications] couldn't update the card for " + card.ign + ": " + e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- decisions

    private void accept(ButtonInteractionEvent event, InteractionHook hook, String discordId) {
        Card card = load(discordId);
        if (card == null || !card.state.equals("pending")) {
            hook.editOriginal("This application was already decided.").queue();
            return;
        }
        User applicant;
        try {
            applicant = event.getJDA().retrieveUserById(discordId).complete();
        } catch (RuntimeException e) {
            hook.editOriginal("Couldn't find that Discord account any more.").queue();
            return;
        }
        // The same as /whitelist add: sheet, link, whitelist, role, nickname and the welcome DM. Its reply lands here.
        if (!whitelist.add(hook, event.getUser(), applicant, card.edition, card.ign, false, card.bannedBefore)) {
            return;
        }
        clear(discordId);
        card.state = "accepted";
        card.decision = event.getUser().getAsMention() + " <t:" + System.currentTimeMillis() / 1000 + ":R>";
        redraw(event.getMessage(), card);
        logger.info("[applications] " + event.getUser().getName() + " accepted " + card.ign + " (" + discordId + ")");
    }

    private void deny(JDA jda, InteractionHook hook, User staff, Message message, String discordId, String reason) {
        Card card = load(discordId);
        if (card == null || !card.state.equals("pending")) {
            hook.editOriginal("This application was already decided.").queue();
            return;
        }
        clear(discordId);
        setValue("app.denied." + discordId, String.valueOf(System.currentTimeMillis()));
        long again = System.currentTimeMillis() / 1000 + config.reapplyDays() * 86_400L;
        Container dm = Container.of(
                TextDisplay.of("### " + Glyph.NO + " Your application wasn't accepted\n-# " + io.github.lrdrandomplayz.smplink.Branding.name() + " · `" + card.ign + "`"),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of("> " + reason.replace("\n", "\n> ")),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of(config.reapplyDays() > 0 ? Glyph.CALENDAR + " You can apply again <t:" + again + ":R>."
                        : Glyph.CALENDAR + " You can apply again whenever you're ready.")
        ).withAccentColor(Embeds.DANGER);
        boolean told;
        try {
            jda.retrieveUserById(discordId).complete().openPrivateChannel().complete()
                    .sendMessageComponents(dm).useComponentsV2().complete();
            told = true;
        } catch (RuntimeException e) {
            told = false;
        }
        card.state = "denied";
        card.decision = staff.getAsMention() + " <t:" + System.currentTimeMillis() / 1000 + ":R>";
        card.reason = reason;
        redraw(message, card);
        hook.editOriginal(told ? "Denied, and they got a DM with the reason." : Glyph.WARN + " Denied, but their DMs are closed, so they weren't told.")
                .queue();
        logger.info("[applications] " + staff.getName() + " denied " + card.ign + " (" + discordId + ")");
    }

    /** A private thread with the applicant in the panel's channel (they can't see the staff review channel). */
    private void ask(ButtonInteractionEvent event, InteractionHook hook, String discordId) {
        Card card = load(discordId);
        if (card == null) {
            hook.editOriginal("Couldn't find this application.").queue();
            return;
        }
        String channelId = value(PANEL_CHANNEL_KEY);
        TextChannel channel = channelId.isEmpty() ? null : event.getJDA().getTextChannelById(channelId);
        if (channel == null) {
            hook.editOriginal("Post the panel with /applications panel first; questions open as private threads in its channel.").queue();
            return;
        }
        try {
            ThreadChannel thread = channel.createThreadChannel(card.ign + " · application", true).setInvitable(false).complete();
            thread.addThreadMemberById(discordId).complete();
            thread.addThreadMember(event.getUser()).complete();
            thread.sendMessage(Glyph.QUESTION + " <@" + discordId + "> staff have a question about your application. "
                    + event.getUser().getAsMention() + " will ask it here.").complete();
            card.question = thread.getAsMention() + " opened by " + event.getUser().getAsMention();
            redraw(event.getMessage(), card);
            hook.editOriginal("Opened " + thread.getAsMention() + " with them.").queue();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "[applications] couldn't open a question thread", e);
            hook.editOriginal("Couldn't open a thread (the bot needs Create Private Threads in <#" + channelId + ">): "
                    + e.getMessage()).queue();
        }
    }

    // ---------------------------------------------------------------- kv

    private void clear(String discordId) {
        setValue("app.pending." + discordId, "");
    }

    private String value(String key) {
        try {
            String v = SyncBridge.await(tracking.value(key), PlayerService.DB_TIMEOUT_MS);
            return v == null ? "" : v;
        } catch (Exception e) {
            throw new IllegalStateException("database: " + e.getMessage(), e);
        }
    }

    private void setValue(String key, String value) {
        try {
            SyncBridge.await(tracking.setValue(key, value), PlayerService.DB_TIMEOUT_MS);
        } catch (Exception e) {
            logger.warning("[applications] couldn't save " + key + ": " + e.getMessage());
        }
    }
}
