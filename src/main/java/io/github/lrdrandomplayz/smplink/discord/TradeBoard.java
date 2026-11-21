package io.github.lrdrandomplayz.smplink.discord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.FileUpload;
import io.github.lrdrandomplayz.smplink.PluginSettings;
import io.github.lrdrandomplayz.smplink.db.LinkRepository.Link;
import io.github.lrdrandomplayz.smplink.db.TradeRepository;
import io.github.lrdrandomplayz.smplink.db.TradeRepository.Trade;
import io.github.lrdrandomplayz.smplink.http.ApiException;
import io.github.lrdrandomplayz.smplink.http.SyncBridge;
import io.github.lrdrandomplayz.smplink.inventory.ItemView;
import io.github.lrdrandomplayz.smplink.service.PlayerService;

/**
 * /trade: linked members pick items and fill in a form, and the bot posts their offer to the trade
 * channel with a villager-style picture of the items. Only the poster can mark it traded (see
 * {@link TradeReviews}) or remove it; posts still open after trade.expire-days are expired.
 * /trades searches the open posts.
 */
final class TradeBoard {

    /** One stack in a trade picture, e.g. 64 of "diamond". */
    record Stack(String item, int amount) {
    }

    static final String OFFER_FORM = "trade:form:offer";
    static final String WANT_FORM = "trade:form:want";
    // Buttons on the private step message between and after the two forms.
    private static final String NEXT_BUTTON = "trade:step:want";
    private static final String EDIT_OFFER_BUTTON = "trade:step:offer";
    static final String DONE_PREFIX = "trade:done:";
    static final String DELETE_PREFIX = "trade:del:";
    static final int MAX_QUANTITY = 999;
    private static final long DRAFT_TIMEOUT_MS = 15 * 60 * 1000;
    // Fields dropped once a trade is closed: the expiry and the invitation to negotiate no longer apply.
    private static final String EXPIRES_FIELD = "⏳ Expires";
    private static final String NEGOTIATE_FIELD = "💬 Interested?";
    private static final String IMAGE = "trade.png";
    private static final int SEARCH_LIMIT = 10;
    // Items vanilla draws with the enchantment glint even without enchantments.
    private static final Set<String> GLINT = Set.of("enchanted_book", "enchanted_golden_apple", "experience_bottle", "nether_star");

    /**
     * A trade being filled in: what was typed in each form (to fill it back in when something needs
     * fixing) and the checked offer once the first form is valid.
     */
    private static final class Draft {
        final String[] offerTyped = {"", "", "", ""};
        final String[] wantTyped = {"", "", "", ""};
        List<Stack> offer = List.of();
        long touchedAt = System.currentTimeMillis();
    }

    private final PlayerService players;
    private final TradeRepository trades;
    private final InventoryRenderer renderer;
    private final PluginSettings settings;
    private final Logger logger;
    private final RateLimiter postLimiter;
    private final Map<String, Draft> drafts = new ConcurrentHashMap<>();

    TradeBoard(PlayerService players, TradeRepository trades, InventoryRenderer renderer, PluginSettings settings, Logger logger) {
        this.players = players;
        this.trades = trades;
        this.renderer = renderer;
        this.settings = settings;
        this.logger = logger;
        this.postLimiter = new RateLimiter(settings.tradePostsPerHour(), 60L * 60 * 1000);
    }



    static List<SlashCommandData> definitions() {
        return List.of(
                Commands.slash("trade", "Post a trade offer (up to two Minecraft items for one) to the trade channel"),
                Commands.slash("trades", "Search open trade offers").addOptions(
                        new OptionData(OptionType.STRING, "item", "An item to look for").setAutoComplete(true),
                        new OptionData(OptionType.USER, "user", "Only this member's trades")));
    }

    static boolean isComponent(String id) {
        return id.startsWith("trade:");
    }

    static boolean isForm(String id) {
        return id.equals(OFFER_FORM) || id.equals(WANT_FORM);
    }

    static boolean isStepButton(String id) {
        return id.equals(NEXT_BUTTON) || id.equals(EDIT_OFFER_BUTTON);
    }

    // --- posting: /trade, then the offer form, then the "looking for" form --------------------------------

    /** Modals must be the first reply, so this answers straight away instead of deferring. */
    void openForm(SlashCommandInteractionEvent event) {
        if (settings.tradeChannelId().isEmpty()) {
            event.reply("Trading is turned off on this server.").setEphemeral(true).queue();
            return;
        }
        String problem = cannotPost(event.getUser().getId());
        if (problem != null) {
            event.reply(problem).setEphemeral(true).queue();
            return;
        }
        long now = System.currentTimeMillis();
        drafts.values().removeIf(draft -> now - draft.touchedAt > DRAFT_TIMEOUT_MS);
        Draft draft = new Draft();
        drafts.put(event.getUser().getId(), draft);
        event.replyModal(offerForm(draft)).queue();
    }

    /** Next and Change offer on the step message; each opens the matching form, filled in with what was typed. */
    void onStepButton(ButtonInteractionEvent event) {
        Draft draft = drafts.get(event.getUser().getId());
        if (draft == null || System.currentTimeMillis() - draft.touchedAt > DRAFT_TIMEOUT_MS) {
            event.editMessage(Glyph.TIME + " This trade form expired. Run **/trade** to start again.").setComponents().queue();
            return;
        }
        draft.touchedAt = System.currentTimeMillis();
        event.replyModal(event.getComponentId().equals(NEXT_BUTTON) ? wantForm(draft) : offerForm(draft)).queue();
    }

    private static Modal offerForm(Draft draft) {
        String[] typed = draft.offerTyped;
        return Modal.create(OFFER_FORM, "Post a trade · 1 of 2: your offer")
                .addComponents(
                        Label.of("Offering (Minecraft item)", input("offer_item", "e.g. Diamond", typed[0], true, 60)),
                        Label.of("Quantity", input("offer_qty", "1 to " + MAX_QUANTITY + ", e.g. 64", typed[1], true, 3)),
                        Label.of("Second item (optional)", input("offer2_item", "e.g. Gold Ingot", typed[2], false, 60)),
                        Label.of("Second item's quantity", input("offer2_qty", "1 to " + MAX_QUANTITY + ", e.g. 16", typed[3], false, 3)))
                .build();
    }

    private static Modal wantForm(Draft draft) {
        String[] typed = draft.wantTyped;
        return Modal.create(WANT_FORM, "Post a trade · 2 of 2: what you want")
                .addComponents(
                        Label.of("Looking for (Minecraft item)", input("want_item", "e.g. Elytra", typed[0], true, 60)),
                        Label.of("Quantity", input("want_qty", "1 to " + MAX_QUANTITY + ", e.g. 1", typed[1], true, 3)),
                        Label.of("Second item (optional)", input("want2_item", "e.g. Totem of Undying", typed[2], false, 60)),
                        Label.of("Second item's quantity", input("want2_qty", "1 to " + MAX_QUANTITY + ", e.g. 2", typed[3], false, 3)))
                .build();
    }

    private static TextInput input(String id, String placeholder, String value, boolean required, int maxLength) {
        TextInput.Builder input = TextInput.create(id, TextInputStyle.SHORT)
                .setPlaceholder(placeholder).setRequired(required).setMaxLength(maxLength);
        if (value != null && !value.isBlank()) {
            input.setValue(value);
        }
        return input.build();
    }

    /** Null if the member may post now, otherwise why not. */
    private String cannotPost(String discordId) {
        try {
            if (linkOf(discordId).isEmpty()) {
                return "Only linked members can post trades. Links come from the server's sign-up sheet.";
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "/trade link lookup failed", e);
            return "Could not check your link right now. Try again in a moment.";
        }
        long waitMs = postLimiter.peek(discordId);
        if (waitMs > 0) {
            return "You can post " + settings.tradePostsPerHour() + " trades an hour. Try again <t:"
                    + (System.currentTimeMillis() + waitMs) / 1000 + ":R>.";
        }
        return null;
    }

    Optional<Link> linkOf(String discordId) throws Exception {
        try {
            return Optional.of(players.link(discordId));
        } catch (ApiException e) {
            return Optional.empty();
        }
    }

    /**
     * The item ID for what was typed ("Diamond Sword", "minecraft:elytra", "diamonds"), or empty if it
     * isn't a Minecraft item. A trailing "s" or "es" is dropped if that makes it one.
     */
    static Optional<String> resolveItem(String typed, java.util.function.Predicate<String> exists) {
        String id = normalise(typed);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        for (String candidate : List.of(id, id.endsWith("es") ? id.substring(0, id.length() - 2) : "",
                id.endsWith("s") ? id.substring(0, id.length() - 1) : "")) {
            if (!candidate.isEmpty() && exists.test(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** 1 to {@link #MAX_QUANTITY}, or -1 if it isn't a whole number in that range. */
    static int parseQuantity(String typed) {
        try {
            int quantity = Integer.parseInt(typed.trim());
            return quantity >= 1 && quantity <= MAX_QUANTITY ? quantity : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The checked stack, or null after adding what's wrong with it to {@code problems}. */
    private Stack readStack(String field, String item, String quantity, List<String> problems) {
        Optional<String> id = resolveItem(item, renderer::hasItem);
        if (id.isEmpty()) {
            List<String> close = matchingItems(renderer.itemIds(), item).stream().limit(3).map(Embeds::titleCase).toList();
            problems.add("**" + field + "**: `" + item + "` isn't a Minecraft item."
                    + (close.isEmpty() ? "" : " Did you mean " + String.join(", ", close) + "?"));
        }
        int amount = parseQuantity(quantity);
        if (amount < 0) {
            problems.add("**" + field + " quantity**: `" + (quantity.isEmpty() ? " " : quantity)
                    + "` must be a whole number from 1 to " + MAX_QUANTITY + ".");
        }
        return id.isPresent() && amount > 0 ? new Stack(id.get(), amount) : null;
    }

    /** Answers a form by editing the step message it came from, or with a new private message for the first form. */
    private static void respond(ModalInteractionEvent event, java.util.function.Consumer<InteractionHook> work) {
        if (event.getMessage() != null) {
            event.deferEdit().queue(work);
        } else {
            event.deferReply(true).queue(work);
        }
    }

    void onForm(ModalInteractionEvent event, ExecutorService pool) {
        String userId = event.getUser().getId();
        Draft draft = drafts.get(userId);
        if (draft == null) {
            event.reply(Glyph.TIME + " This trade form expired. Run **/trade** to start again.").setEphemeral(true).queue();
            return;
        }
        draft.touchedAt = System.currentTimeMillis();
        if (event.getModalId().equals(OFFER_FORM)) {
            draft.offerTyped[0] = value(event.getValue("offer_item"));
            draft.offerTyped[1] = value(event.getValue("offer_qty"));
            draft.offerTyped[2] = value(event.getValue("offer2_item"));
            draft.offerTyped[3] = value(event.getValue("offer2_qty"));
            respond(event, hook -> pool.execute(() -> offerSubmitted(hook, draft)));
        } else {
            draft.wantTyped[0] = value(event.getValue("want_item"));
            draft.wantTyped[1] = value(event.getValue("want_qty"));
            draft.wantTyped[2] = value(event.getValue("want2_item"));
            draft.wantTyped[3] = value(event.getValue("want2_qty"));
            respond(event, hook -> pool.execute(() -> wantSubmitted(hook, event.getJDA(), userId, draft)));
        }
    }

    /**
     * A form's one or two stacks from {@code typed} (item, quantity, second item, second quantity), adding
     * anything wrong to {@code problems}. The second quantity may be left empty for one of them.
     */
    private List<Stack> readStacks(String field, String[] typed, List<String> problems) {
        List<Stack> stacks = new ArrayList<>();
        Stack first = readStack(field, typed[0], typed[1], problems);
        if (first != null) {
            stacks.add(first);
        }
        if (!typed[2].isEmpty()) {
            Stack second = readStack("Second item", typed[2], typed[3].isEmpty() ? "1" : typed[3], problems);
            if (second != null && first != null && second.item().equals(first.item())) {
                problems.add("**Second item** is the same as the first. Put the total in the first quantity instead.");
            } else if (second != null) {
                stacks.add(second);
            }
        } else if (!typed[3].isEmpty()) {
            problems.add("**Second item's quantity** is filled in, but the second item is empty.");
        }
        return stacks;
    }

    private void offerSubmitted(InteractionHook hook, Draft draft) {
        List<String> problems = new ArrayList<>();
        List<Stack> offer = readStacks("Offering", draft.offerTyped, problems);
        if (!problems.isEmpty()) {
            hook.editOriginal(Glyph.NO + " **Your offer needs fixing:**\n• " + String.join("\n• ", problems)
                            + "\n\nTip: `/trades item:` suggests exact item names as you type.")
                    .setComponents(ActionRow.of(Button.primary(EDIT_OFFER_BUTTON, "Fix my offer").withEmoji(Glyph.EDIT.emoji())))
                    .queue();
            return;
        }
        draft.offer = List.copyOf(offer);
        hook.editOriginal(Glyph.YES + " **Step 1 of 2 done.** You're offering **" + describe(offer) + "**.\n"
                        + "Now press **Next** and say what you want in return.")
                .setComponents(ActionRow.of(
                        Button.success(NEXT_BUTTON, "Next: what you want").withEmoji(Glyph.ARROW.emoji()),
                        Button.secondary(EDIT_OFFER_BUTTON, "Change offer").withEmoji(Glyph.EDIT.emoji())))
                .queue();
    }

    private void wantSubmitted(InteractionHook hook, JDA jda, String userId, Draft draft) {
        try {
            if (draft.offer.isEmpty()) {
                hook.editOriginal("Fill in your offer first.")
                        .setComponents(ActionRow.of(Button.primary(EDIT_OFFER_BUTTON, "Fill in my offer"))).queue();
                return;
            }
            List<String> problems = new ArrayList<>();
            List<Stack> want = readStacks("Looking for", draft.wantTyped, problems);
            if (!problems.isEmpty()) {
                hook.editOriginal(Glyph.NO + " **What you want needs fixing:**\n• " + String.join("\n• ", problems)
                                + "\n\nYour offer (**" + describe(draft.offer) + "**) is kept.")
                        .setComponents(ActionRow.of(
                                Button.primary(NEXT_BUTTON, "Fix what I want").withEmoji(Glyph.EDIT.emoji()),
                                Button.secondary(EDIT_OFFER_BUTTON, "Change offer")))
                        .queue();
                return;
            }
            Optional<Link> link = linkOf(userId);
            if (link.isEmpty()) {
                hook.editOriginal("Only linked members can post trades.").setComponents().queue();
                return;
            }
            long waitMs = postLimiter.take(userId);
            if (waitMs > 0) {
                hook.editOriginal("You can post " + settings.tradePostsPerHour() + " trades an hour. Try again <t:"
                        + (System.currentTimeMillis() + waitMs) / 1000 + ":R>.").setComponents().queue();
                return;
            }
            GuildMessageChannel channel = Channels.find(jda, settings.tradeChannelId(), "trade.channel-id", logger);
            if (channel == null) {
                hook.editOriginal("The trade channel could not be found. Tell an admin to check trade.channel-id.")
                        .setComponents().queue();
                return;
            }
            long now = System.currentTimeMillis();
            String ign = link.get().name();
            List<Stack> offer = draft.offer;
            byte[] picture = picture(offer, want);
            Trade draftTrade = Trade.open("", channel.getId(), userId, ign, now, describe(offer), describe(want),
                    encode(offer), encode(want));
            var send = channel.sendMessageComponents(post(draftTrade, 0, now + expireMs(), settings.tradeFooter(),
                            settings.tradeMiddlemanRoleId(), picture != null, null, null))
                    .useComponentsV2()
                    .setAllowedMentions(List.of());
            if (picture != null) {
                send = send.addFiles(FileUpload.fromData(picture, IMAGE));
            }
            Message posted = send.complete();
            drafts.remove(userId, draft);
            SyncBridge.await(trades.add(Trade.open(posted.getId(), channel.getId(), userId, ign, now,
                    describe(offer), describe(want), encode(offer), encode(want))), PlayerService.DB_TIMEOUT_MS);
            logger.info("[trade] " + ign + " (" + userId + ") posted a trade: " + posted.getJumpUrl());
            hook.editOriginal(Glyph.YES + " **Your trade is posted:** " + posted.getJumpUrl()
                            + "\nPeople who press **I'm interested** get listed for you, and you'll get a DM for each. When it's "
                            + "done, press **Mark traded** and pick who you traded with; once they confirm, you'll both get a "
                            + "DM to vouch for each other or report a scam.")
                    .setComponents().queue();
        } catch (Exception e) {
            logger.log(Level.WARNING, "/trade post by " + userId + " failed", e);
            hook.editOriginal("Could not post your trade. The error has been logged in the server console.").setComponents().queue();
        }
    }

    /** The villager-style picture (second slots only when there are second items), or null if it couldn't be drawn. */
    private byte[] picture(List<Stack> offer, List<Stack> want) {
        try {
            return renderer.renderTrade(view(offer.get(0)), offer.size() < 2 ? null : view(offer.get(1)),
                    view(want.get(0)), want.size() < 2 ? null : view(want.get(1)));
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not draw a trade picture", e);
            return null;
        }
    }

    private static ItemView view(Stack stack) {
        return new ItemView(0, stack.item(), stack.amount(), null,
                GLINT.contains(stack.item()) ? Map.of("glint", 1) : null, 0, 0);
    }

    // --- buttons on the post -------------------------------------------------------------------------------

    /** Remove deletes the post, for the poster only. Mark traded is handled by {@link TradeReviews}. */
    void onRemove(ButtonInteractionEvent event, ExecutorService pool) {
        String owner = event.getComponentId().substring(DELETE_PREFIX.length());
        if (!owner.equals(event.getUser().getId())) {
            event.reply("Only <@" + owner + "> can remove this trade.").setEphemeral(true).setAllowedMentions(List.of()).queue();
            return;
        }
        Message message = event.getMessage();
        event.deferEdit().queue(hook -> message.delete().queue());
        pool.execute(() -> {
            try {
                SyncBridge.await(trades.close(message.getId(), "removed"), PlayerService.DB_TIMEOUT_MS);
                archiveThreads(event.getJDA(), message.getId(), "The poster removed this trade.");
                logger.info("[trade] " + message.getId() + " removed by " + event.getUser().getName());
            } catch (Exception e) {
                logger.log(Level.WARNING, "Could not record trade " + message.getId() + " as removed", e);
            }
        });
    }

    /** Scheduled: greys out trades that have been open longer than trade.expire-days. */
    void expireOld(JDA jda) throws Exception {
        long now = System.currentTimeMillis();
        List<Trade> old = SyncBridge.await(trades.openOlderThan(now - expireMs()), PlayerService.DB_TIMEOUT_MS);
        for (Trade trade : old) {
            String status = "expired";
            try {
                GuildMessageChannel channel = jda.getChannelById(GuildMessageChannel.class, trade.channelId());
                if (channel == null) {
                    status = "gone";
                } else {
                    Message message = channel.retrieveMessageById(trade.messageId()).complete();
                    refreshPost(message, trade, Glyph.TIME + " Expired",
                            "Expired <t:" + Instant.now().getEpochSecond() + ":R>. Post a new trade with /trade.");
                }
            } catch (ErrorResponseException e) {
                if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) {
                    logger.warning("[trade] could not expire " + trade.messageId() + ": " + e.getMeaning());
                    continue;
                }
                status = "gone";
            }
            SyncBridge.await(trades.close(trade.messageId(), status), PlayerService.DB_TIMEOUT_MS);
            archiveThreads(jda, trade.messageId(), "This trade expired.");
            logger.info("[trade] " + trade.ign() + "'s trade " + trade.messageId() + " " + status);
        }
    }

    // --- /trades and autocomplete ------------------------------------------------------------------------

    /** Runs on a worker thread after an ephemeral defer. */
    void search(InteractionHook hook, String query, User user) throws Exception {
        String item = query == null || query.isBlank() ? null : query.trim();
        List<Trade> found = SyncBridge.await(trades.search(item, user == null ? null : user.getId(), SEARCH_LIMIT),
                PlayerService.DB_TIMEOUT_MS);
        String what = item == null ? "" : " for **" + Embeds.titleCase(item) + "**";
        String whose = user == null ? "" : " by <@" + user.getId() + ">";
        EmbedBuilder embed = Embeds.base(Embeds.BRAND).setTitle(Glyph.SEARCH + " Open trades");
        if (found.isEmpty()) {
            embed.setDescription("No open trades" + what + whose + " right now. Post your own with /trade.");
        } else {
            StringBuilder text = new StringBuilder("Newest first" + what + whose + ":\n\n");
            for (Trade trade : found) {
                text.append("**").append(summary(trade.offerItems(), trade.offering())).append("** ➜ **")
                        .append(summary(trade.wantItems(), trade.wanted())).append("**\n<@").append(trade.discordId())
                        .append("> · `").append(trade.ign()).append("` · <t:").append(trade.createdAt() / 1000)
                        .append(":R> · [View post](https://discord.com/channels/").append(settings.guildId()).append('/')
                        .append(trade.channelId()).append('/').append(trade.messageId()).append(")\n\n");
            }
            embed.setDescription(text.toString().trim());
            if (found.size() == SEARCH_LIMIT) {
                embed.setFooter("Showing the " + SEARCH_LIMIT + " newest. Search for an item to narrow it down.");
            }
        }
        hook.editOriginalEmbeds(embed.build()).setAllowedMentions(List.of()).queue();
    }

    void onAutoComplete(CommandAutoCompleteInteractionEvent event) {
        if (!event.getFocusedOption().getName().equals("item")) {
            event.replyChoices(List.of()).queue();
            return;
        }
        event.replyChoices(matchingItems(renderer.itemIds(), event.getFocusedOption().getValue()).stream()
                .map(id -> new Command.Choice(Embeds.titleCase(id), id))
                .toList()).queue();
    }

    /** Up to 25 item IDs for what's been typed: shortest names starting with a word of it first, then any match. */
    static List<String> matchingItems(List<String> ids, String typed) {
        String query = normalise(typed);
        List<String> starts = new ArrayList<>();
        List<String> contains = new ArrayList<>();
        for (String id : ids) {
            if (id.startsWith(query) || id.contains("_" + query)) {
                starts.add(id);
            } else if (id.contains(query)) {
                contains.add(id);
            }
        }
        starts.sort(java.util.Comparator.comparingInt(String::length).thenComparing(java.util.Comparator.naturalOrder()));
        starts.addAll(contains);
        return starts.stream().limit(25).toList();
    }

    // --- helpers ---------------------------------------------------------------------------------------

    /** How long a trade stays open (trade.expire-days). */
    private long expireMs() {
        return Math.round(settings.tradeExpireDays() * 24 * 60 * 60 * 1000);
    }

    private static String value(ModalMapping mapping) {
        return mapping == null ? "" : mapping.getAsString().trim();
    }

    static String normalise(String typed) {
        String id = typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** "64 × Diamond, 1 × Enchanted Book". */
    static String describe(List<Stack> stacks) {
        return stacks.stream().map(s -> s.amount() + " × " + Embeds.titleCase(s.item())).collect(Collectors.joining(", "));
    }

    static String encode(List<Stack> stacks) {
        return stacks.isEmpty() ? null : stacks.stream().map(s -> s.item() + ":" + s.amount()).collect(Collectors.joining(","));
    }

    static List<Stack> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        List<Stack> stacks = new ArrayList<>();
        for (String part : encoded.split(",")) {
            String[] pieces = part.split(":");
            int amount = 1;
            try {
                amount = pieces.length > 1 ? Integer.parseInt(pieces[1]) : 1;
            } catch (NumberFormatException ignored) {
                // Stays 1.
            }
            stacks.add(new Stack(pieces[0], amount));
        }
        return stacks;
    }

    /** One line for /trades: the picked items if there are any, otherwise the start of the text. */
    static String summary(String items, String text) {
        List<Stack> stacks = decode(items);
        if (!stacks.isEmpty()) {
            return describe(stacks);
        }
        String line = text == null ? "?" : text.replaceAll("\\s+", " ").trim();
        return line.length() > 60 ? line.substring(0, 57) + "…" : line;
    }

    // --- embeds -----------------------------------------------------------------------------------------

    static final String INTEREST_PREFIX = "trade:interest:";
    static final String MIDDLEMAN_PREFIX = "trade:mm:";
    static final String CLAIM_PREFIX = "trade:mmclaim:";
    static final String CANCEL_MM_PREFIX = "trade:mmcancel:";
    // Old-style posts younger than this are converted to cards at startup.
    private static final int CONVERT_DAYS = 60;
    // How long the reply that pings the middleman role stays before it's deleted.
    private static final int PING_SECONDS = 5;

    /**
     * A trade post as one Components V2 card, the way Dank Memer lays out its messages: the header with
     * the poster's head, the items, the picture, the invitation to negotiate and the buttons, all inside
     * one container. {@code closedTitle} is null while the trade is open; once it's closed the card turns
     * grey, shows {@code closedNote} and loses its buttons.
     */
    static Container post(Trade trade, int interested, long expiresAt, String footer, String middlemanRoleId,
                          boolean picture, String closedTitle, String closedNote) {
        boolean open = closedTitle == null;
        boolean middlemen = !middlemanRoleId.isEmpty();
        List<ContainerChildComponent> parts = new ArrayList<>();
        parts.add(Section.of(Thumbnail.fromUrl(Embeds.skin("avatar", trade.ign())),
                TextDisplay.of("## " + (open ? Glyph.TRADE + " Trade offer" : closedTitle)
                        + "\nPosted by <@" + trade.discordId() + "> · " + Glyph.PLAYER + " `" + trade.ign() + "`" + (open ? "" : "\n**" + closedNote + "**"))));
        parts.add(Separator.createDivider(Separator.Spacing.SMALL));
        parts.add(TextDisplay.of("### " + Glyph.PACKAGE + " Offering\n" + itemsOrText(trade.offerItems(), trade.offering())));
        parts.add(TextDisplay.of("### " + Glyph.TARGET + " Looking for\n" + itemsOrText(trade.wantItems(), trade.wanted())));
        if (picture) {
            parts.add(MediaGallery.of(MediaGalleryItem.fromUrl("attachment://" + IMAGE)));
        }
        // The role itself is pinged by a short-lived reply (see onMiddleman); edits never notify anyone.
        String middleman = "";
        if (trade.middlemanId() != null) {
            middleman = Glyph.SHIELD + " **Middleman:** <@" + trade.middlemanId() + "> (requested by <@" + trade.middlemanBy() + ">)"
                    + (trade.threadId() == null ? "" : " · <#" + trade.threadId() + ">");
        } else if (trade.middlemanBy() != null && middlemen) {
            middleman = Glyph.SHIELD + " **Middleman requested** by <@" + trade.middlemanBy() + "> · waiting for a middleman to claim it";
        } else if (open && trade.middlemanCancelledBy() != null && middlemen) {
            middleman = "-# " + Glyph.SHIELD + " Middleman cancelled by <@" + trade.middlemanCancelledBy() + ">";
        }
        if (open) {
            parts.add(TextDisplay.of(Glyph.TIME + " Expires <t:" + expiresAt / 1000 + ":R>\n" + Glyph.CHAT + " Press **I'm interested** to open a private chat with <@"
                    + trade.discordId() + "> · " + Glyph.PLAYER + " Trades are done in game" + (middleman.isEmpty() ? "" : "\n" + middleman)));
            parts.add(Separator.createDivider(Separator.Spacing.SMALL));
            parts.add(ActionRow.of(openButtons(trade, interested, middlemen)));
        } else if (trade.middlemanId() != null) {
            parts.add(TextDisplay.of(middleman));
        }
        parts.add(TextDisplay.of("-# " + Glyph.WARN + " " + footer + " · Posted <t:" + trade.createdAt() / 1000 + ":f>"));
        return Container.of(parts).withAccentColor(open ? Embeds.BRAND : Embeds.MUTED);
    }

    /** I'm interested, the middleman button for the trade's state, Cancel middleman when there is one, Mark traded, Remove. */
    private static List<Button> openButtons(Trade trade, int interested, boolean middlemen) {
        String owner = trade.discordId();
        List<Button> buttons = new ArrayList<>();
        buttons.add(Button.primary(INTEREST_PREFIX + owner,
                interested == 0 ? "I'm interested" : "I'm interested (" + interested + ")").withEmoji(Glyph.BELL.emoji()));
        if (middlemen) {
            Emoji shield = Glyph.SHIELD.emoji();
            if (trade.middlemanBy() == null) {
                boolean spent = trade.middlemanRequests() >= TradeRepository.MAX_MIDDLEMAN_REQUESTS;
                buttons.add(Button.secondary(MIDDLEMAN_PREFIX + owner, spent ? "Middleman limit reached" : "Request Middleman")
                        .withEmoji(shield).withDisabled(spent));
            } else {
                // Only members with the middleman role can claim it (checked in onClaim).
                buttons.add(trade.middlemanId() == null
                        ? Button.primary(CLAIM_PREFIX + owner, "I'll middleman this").withEmoji(shield)
                        : Button.secondary(CLAIM_PREFIX + owner, "Middleman on it").withEmoji(shield).asDisabled());
                buttons.add(Button.secondary(CANCEL_MM_PREFIX + owner, "Cancel middleman").withEmoji(Glyph.NO.emoji()));
            }
        }
        buttons.add(Button.success(DONE_PREFIX + owner, "Mark traded").withEmoji(Glyph.YES.emoji()));
        buttons.add(Button.danger(DELETE_PREFIX + owner, "Remove").withEmoji(Glyph.DELETE.emoji()));
        return buttons;
    }

    /**
     * Redraws a post from its stored trade: {@code closedTitle} null for an open post (e.g. a new interest
     * count), otherwise closed with the note. A post from before 1.20 (a plain embed) is turned into a card
     * too; if Discord won't convert it, the old embed is updated instead.
     */
    void refreshPost(Message message, Trade trade, String closedTitle, String closedNote) throws Exception {
        if (!message.isUsingComponentsV2()) {
            try {
                drawCard(message, trade, closedTitle, closedNote);
                return;
            } catch (ErrorResponseException e) {
                logger.info("[trade] could not convert post " + message.getId() + " to a card, updating the old one: " + e.getMeaning());
            }
            int interested = SyncBridge.await(trades.interested(trade.messageId()), PlayerService.DB_TIMEOUT_MS).size();
            if (closedTitle != null && !message.getEmbeds().isEmpty()) {
                message.editMessageEmbeds(closed(message.getEmbeds().get(0), closedTitle, closedNote))
                        .setComponents().setAllowedMentions(List.of()).complete();
            } else if (closedTitle == null) {
                message.editMessageComponents(postButtons(trade.discordId(), interested)).complete();
            }
            return;
        }
        drawCard(message, trade, closedTitle, closedNote);
    }

    /** Replaces the whole message with the trade's card (dropping an old embed), with a freshly drawn picture. */
    private void drawCard(Message message, Trade trade, String closedTitle, String closedNote) throws Exception {
        int interested = SyncBridge.await(trades.interested(trade.messageId()), PlayerService.DB_TIMEOUT_MS).size();
        List<Stack> offer = decode(trade.offerItems());
        List<Stack> want = decode(trade.wantItems());
        byte[] picture = offer.isEmpty() || want.isEmpty() ? null : picture(offer, want);
        var edit = message.editMessageComponents(post(trade, interested, trade.createdAt() + expireMs(), settings.tradeFooter(),
                        settings.tradeMiddlemanRoleId(), picture != null, closedTitle, closedNote))
                .useComponentsV2()
                .setReplace(true)
                .setAllowedMentions(List.of());
        // The picture is drawn again and attached, so the card never points at a missing file.
        (picture == null ? edit.setFiles() : edit.setFiles(FileUpload.fromData(picture, IMAGE))).complete();
    }

    /**
     * I'll middleman this: a member with the middleman role takes the trade. They're recorded on it (so the
     * traders can rate them afterwards if they really handled it) and join the conversation: the private
     * thread of whoever asked for them, or a new one with the poster and the requester.
     */
    void onClaim(ButtonInteractionEvent event, ExecutorService pool) {
        String role = settings.tradeMiddlemanRoleId();
        if (!BotListener.hasRole(event.getMember(), role)) {
            event.reply("Only members with the <@&" + role + "> role can middleman a trade.")
                    .setEphemeral(true).setAllowedMentions(List.of()).queue();
            return;
        }
        Message post = event.getMessage();
        String middlemanId = event.getUser().getId();
        event.deferReply(true).queue(hook -> pool.execute(() -> {
            try {
                Optional<Trade> found = SyncBridge.await(trades.find(post.getId()), PlayerService.DB_TIMEOUT_MS);
                if (found.isPresent() && middlemanId.equals(found.get().discordId())) {
                    hook.editOriginal("You can't middleman your own trade.").queue();
                    return;
                }
                if (!SyncBridge.await(trades.claimMiddleman(post.getId(), middlemanId), PlayerService.DB_TIMEOUT_MS)) {
                    hook.editOriginal("Another middleman has already taken this trade, or it's closed.").queue();
                    return;
                }
                Trade trade = SyncBridge.await(trades.find(post.getId()), PlayerService.DB_TIMEOUT_MS).orElseThrow();
                String threadNote = joinThread(post, trade, middlemanId);
                trade = SyncBridge.await(trades.find(post.getId()), PlayerService.DB_TIMEOUT_MS).orElseThrow();
                refreshPost(post, trade, null, null);
                hook.editOriginal(Glyph.SHIELD + " You're the middleman for " + trade.ign() + "'s trade. " + threadNote)
                        .setAllowedMentions(List.of()).queue();
                logger.info("[trade] " + event.getUser().getName() + " is middleman for " + post.getId());
            } catch (Exception e) {
                logger.log(Level.WARNING, "Middleman claim for " + post.getId() + " failed", e);
                hook.editOriginal("Something went wrong. Try again in a moment.").queue();
            }
        }));
    }

    /**
     * Puts the middleman in the conversation: the requester's negotiation thread when there is one (they
     * asked for a middleman for that deal), otherwise a new thread with the poster and the requester.
     * Returns a line saying where, or why there isn't one.
     */
    private String joinThread(Message post, Trade trade, String middlemanId) throws Exception {
        String requester = trade.middlemanBy();
        if (!requester.equals(trade.discordId())) {
            for (TradeRepository.Interest interest : SyncBridge.await(trades.interested(trade.messageId()), PlayerService.DB_TIMEOUT_MS)) {
                ThreadChannel thread = interest.discordId().equals(requester) && interest.threadId() != null
                        ? post.getJDA().getThreadChannelById(interest.threadId()) : null;
                if (thread != null) {
                    try {
                        thread.sendMessage(Glyph.SHIELD + " <@" + middlemanId + "> has joined as the middleman for this trade.")
                                .setAllowedMentions(java.util.EnumSet.of(Message.MentionType.USER)).complete();
                        SyncBridge.await(trades.setThread(trade.messageId(), thread.getId(), false), PlayerService.DB_TIMEOUT_MS);
                        return "You've been added to their private chat: " + thread.getAsMention();
                    } catch (RuntimeException e) {
                        logger.info("[trade] could not add the middleman to " + thread.getId() + ": " + e.getMessage());
                    }
                }
            }
        }
        ThreadChannel thread = openThread(post, Glyph.SHIELD.unicode() + " " + trade.ign() + "'s trade", List.of(middlemanId, trade.discordId(), requester),
                Glyph.SHIELD + " <@" + middlemanId + "> is the middleman for this trade: " + post.getJumpUrl());
        if (thread == null) {
            return "I couldn't open a private thread (the bot needs Create Private Threads here), so talk to the traders by DM.";
        }
        SyncBridge.await(trades.setThread(trade.messageId(), thread.getId(), true), PlayerService.DB_TIMEOUT_MS);
        return "A private thread is open with the traders: " + thread.getAsMention();
    }

    /**
     * Opens a private thread on the trade channel with these members (mentioning them adds them), or returns
     * null if the channel can't have one or the bot isn't allowed to.
     */
    private ThreadChannel openThread(Message post, String name, List<String> members, String intro) {
        if (!(post.getChannel() instanceof TextChannel channel)) {
            return null;
        }
        try {
            ThreadChannel thread = channel.createThreadChannel(name.length() > 100 ? name.substring(0, 100) : name, true)
                    .setInvitable(false).complete();
            thread.sendMessage(intro + "\n" + new java.util.LinkedHashSet<>(members).stream().map(id -> "<@" + id + ">")
                            .collect(Collectors.joining(" "))
                            + "\n-# Staff can read this thread. Trades happen in game; this is only a place to arrange them.")
                    .setAllowedMentions(java.util.EnumSet.of(Message.MentionType.USER)).complete();
            return thread;
        } catch (net.dv8tion.jda.api.exceptions.InsufficientPermissionException | ErrorResponseException e) {
            logger.warning("[trade] could not open a private thread on " + post.getId() + ": " + e.getMessage());
            return null;
        }
    }

    /** "I'm interested": a private thread for the poster and this buyer to negotiate in. Null if it couldn't be opened. */
    ThreadChannel openNegotiation(Message post, Trade trade, String buyerId, String buyerIgn) throws Exception {
        ThreadChannel thread = openThread(post, Glyph.CHAT.unicode() + " " + trade.ign() + " ↔ " + buyerIgn, List.of(trade.discordId(), buyerId),
                Glyph.CHAT + " **" + buyerIgn + "** is interested in <@" + trade.discordId() + ">'s trade: " + post.getJumpUrl()
                        + "\nNegotiate here. Need a middleman? Press **Request Middleman** on the post and they'll join this chat.");
        if (thread != null) {
            SyncBridge.await(trades.setInterestThread(trade.messageId(), buyerId, thread.getId()), PlayerService.DB_TIMEOUT_MS);
        }
        return thread;
    }

    /** When a trade closes, its private threads get a last message and are archived (still readable, reopened by posting). */
    void archiveThreads(JDA jda, String messageId, String note) {
        try {
            List<String> ids = new ArrayList<>();
            for (TradeRepository.Interest interest : SyncBridge.await(trades.interested(messageId), PlayerService.DB_TIMEOUT_MS)) {
                if (interest.threadId() != null) {
                    ids.add(interest.threadId());
                }
            }
            SyncBridge.await(trades.find(messageId), PlayerService.DB_TIMEOUT_MS)
                    .filter(trade -> trade.threadId() != null && trade.threadDedicated())
                    .ifPresent(trade -> ids.add(trade.threadId()));
            for (String id : ids) {
                ThreadChannel thread = jda.getThreadChannelById(id);
                if (thread != null && !thread.isArchived()) {
                    thread.sendMessage(Glyph.LOCKED + " " + note).setAllowedMentions(List.of())
                            .queue(sent -> thread.getManager().setArchived(true).queue(null, error -> { }), error -> { });
                }
            }
        } catch (Exception e) {
            logger.log(Level.INFO, "[trade] could not archive the threads of " + messageId, e);
        }
    }

    /**
     * Cancel middleman: the poster, whoever asked for one, or the middleman themselves can drop the
     * middleman (too expensive, not needed after all, or the middleman backs out). They get no rating and no
     * trade on their record. A thread opened just for them is locked and archived; from a negotiation
     * thread they're removed. Another request is possible until the trade has had
     * {@link TradeRepository#MAX_MIDDLEMAN_REQUESTS}.
     */
    void onCancelMiddleman(ButtonInteractionEvent event, ExecutorService pool) {
        Message post = event.getMessage();
        String userId = event.getUser().getId();
        event.deferReply(true).queue(hook -> pool.execute(() -> {
            try {
                Optional<Trade> found = SyncBridge.await(trades.find(post.getId()), PlayerService.DB_TIMEOUT_MS);
                if (found.isEmpty() || found.get().middlemanBy() == null) {
                    hook.editOriginal("There's no middleman on this trade to cancel.").queue();
                    return;
                }
                Trade trade = found.get();
                if (!userId.equals(trade.discordId()) && !userId.equals(trade.middlemanBy()) && !userId.equals(trade.middlemanId())) {
                    hook.editOriginal("Only <@" + trade.discordId() + ">, whoever requested the middleman, or the middleman can cancel it.")
                            .setAllowedMentions(List.of()).queue();
                    return;
                }
                if (!SyncBridge.await(trades.cancelMiddleman(post.getId(), userId), PlayerService.DB_TIMEOUT_MS)) {
                    hook.editOriginal("There's no middleman on this trade to cancel.").queue();
                    return;
                }
                ThreadChannel thread = trade.threadId() == null ? null : post.getJDA().getThreadChannelById(trade.threadId());
                if (thread != null) {
                    try {
                        thread.sendMessage(Glyph.NO + " Middleman cancelled by <@" + userId + ">. The traders will carry on without one.")
                                .setAllowedMentions(List.of()).complete();
                        if (trade.threadDedicated()) {
                            thread.getManager().setLocked(true).setArchived(true).complete();
                        } else if (trade.middlemanId() != null) {
                            thread.removeThreadMemberById(trade.middlemanId()).complete();
                        }
                    } catch (RuntimeException e) {
                        logger.info("[trade] could not tidy up thread " + thread.getId() + ": " + e.getMessage());
                    }
                }
                Trade updated = SyncBridge.await(trades.find(post.getId()), PlayerService.DB_TIMEOUT_MS).orElseThrow();
                refreshPost(post, updated, null, null);
                int left = TradeRepository.MAX_MIDDLEMAN_REQUESTS - updated.middlemanRequests();
                hook.editOriginal(Glyph.NO + " Middleman cancelled. "
                        + (left > 0 ? "A middleman can be requested again (" + left + " request left on this trade)."
                        : "This trade has used both of its middleman requests.")).queue();
                logger.info("[trade] middleman on " + post.getId() + " cancelled by " + event.getUser().getName());
            } catch (Exception e) {
                logger.log(Level.WARNING, "Cancelling the middleman on " + post.getId() + " failed", e);
                hook.editOriginal("Something went wrong. Try again in a moment.").queue();
            }
        }));
    }

    /** Request Middleman: anyone linked can press it once per open trade; the post then tags the middleman role. */
    void onMiddleman(ButtonInteractionEvent event, ExecutorService pool) {
        Message post = event.getMessage();
        String userId = event.getUser().getId();
        event.deferReply(true).queue(hook -> pool.execute(() -> {
            try {
                if (linkOf(userId).isEmpty()) {
                    hook.editOriginal("Only linked members can trade. Links come from the server's sign-up sheet.").queue();
                    return;
                }
                if (!SyncBridge.await(trades.requestMiddleman(post.getId(), userId), PlayerService.DB_TIMEOUT_MS)) {
                    hook.editOriginal("A middleman is already requested for this trade, it's used up its "
                            + TradeRepository.MAX_MIDDLEMAN_REQUESTS + " requests, or it's closed.").queue();
                    return;
                }
                Trade trade = SyncBridge.await(trades.find(post.getId()), PlayerService.DB_TIMEOUT_MS).orElseThrow();
                refreshPost(post, trade, null, null);
                // Only a new message notifies anyone, so the role is pinged by a reply to the post, which is then
                // removed to keep the channel tidy. Middlemen still get the notification.
                String role = settings.tradeMiddlemanRoleId();
                post.reply("<@&" + role + "> " + Glyph.SHIELD + " a middleman was requested by <@" + userId + "> for this trade.")
                        .setAllowedMentions(List.of()).mentionRoles(role).mentionRepliedUser(false)
                        .queue(ping -> ping.delete().queueAfter(PING_SECONDS, java.util.concurrent.TimeUnit.SECONDS),
                                error -> logger.warning("[trade] could not ping the middleman role: " + error.getMessage()));
                hook.editOriginal(Glyph.SHIELD + " Middleman requested. <@&" + role + "> has been pinged; a middleman will join your chat.")
                        .setAllowedMentions(List.of()).queue();
                logger.info("[trade] " + event.getUser().getName() + " requested a middleman for " + post.getId());
            } catch (Exception e) {
                logger.log(Level.WARNING, "Middleman request for " + post.getId() + " failed", e);
                hook.editOriginal("Something went wrong. Try again in a moment.").queue();
            }
        }));
    }

    /** The button row of a post from before 1.20 (a plain embed): I'm interested (with a count), Mark traded and Remove. */
    static ActionRow postButtons(String ownerId, int interested) {
        return ActionRow.of(
                Button.primary(INTEREST_PREFIX + ownerId, interested == 0 ? "I'm interested" : "I'm interested (" + interested + ")")
                        .withEmoji(Glyph.BELL.emoji()),
                Button.success(DONE_PREFIX + ownerId, "Mark traded").withEmoji(Glyph.YES.emoji()),
                Button.danger(DELETE_PREFIX + ownerId, "Remove").withEmoji(Glyph.DELETE.emoji()));
    }

    /** The item lines, or for a trade from before items were required, the text that was typed. */
    static String itemsOrText(String items, String text) {
        List<Stack> stacks = decode(items);
        return stacks.isEmpty() ? (text == null || text.isBlank() ? "?" : text) : itemLines(stacks);
    }

    /**
     * The closed title and note a stored trade should show, worked out from its status: null for an open
     * trade. Used when an old post is redrawn without knowing what it said.
     */
    static String[] closedState(Trade trade) {
        return switch (trade.status()) {
            case "traded" -> new String[]{Glyph.YES + " Traded", trade.partnerId() == null ? "Traded"
                    : Boolean.TRUE.equals(trade.confirmed()) ? "Traded with <@" + trade.partnerId() + "> · " + Glyph.YES + " confirmed"
                    : Boolean.FALSE.equals(trade.confirmed()) ? "Marked traded with <@" + trade.partnerId() + ">, who said the trade didn't happen"
                    : "Traded with <@" + trade.partnerId() + "> · " + Glyph.TIME + " waiting for them to confirm"};
            case "expired" -> new String[]{Glyph.TIME + " Expired", "Expired. Post a new trade with /trade."};
            default -> null;
        };
    }

    /**
     * Startup: redraws trade posts from the last 60 days that are still in the pre-1.20 embed style as cards,
     * in place (same message, same spot in the channel). Posts already in the new style are left alone.
     */
    void convertOldPosts(JDA jda) throws Exception {
        List<Trade> posts = SyncBridge.await(trades.postsSince(System.currentTimeMillis() - CONVERT_DAYS * 24L * 60 * 60 * 1000),
                PlayerService.DB_TIMEOUT_MS);
        int converted = 0;
        int failed = 0;
        for (Trade trade : posts) {
            GuildMessageChannel channel = jda.getChannelById(GuildMessageChannel.class, trade.channelId());
            if (channel == null) {
                continue;
            }
            try {
                Message message = channel.retrieveMessageById(trade.messageId()).complete();
                if (message.isUsingComponentsV2()) {
                    continue;
                }
                String[] closed = closedState(trade);
                drawCard(message, trade, closed == null ? null : closed[0], closed == null ? null : closed[1]);
                converted++;
            } catch (ErrorResponseException e) {
                if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) {
                    failed++;
                    logger.warning("[trade] could not convert post " + trade.messageId() + " to the new style: " + e.getMeaning());
                }
            }
        }
        if (converted > 0 || failed > 0) {
            logger.info("[trade] converted " + converted + " old trade post(s) to the new style" + (failed > 0 ? ", " + failed + " failed" : ""));
        }
    }

    /** Each item in bold with "Quantity: **64**" on the line under it, a blank line between items. */
    static String itemLines(List<Stack> stacks) {
        return stacks.stream()
                .map(stack -> "**" + Embeds.titleCase(stack.item()) + "**\nQuantity: **" + stack.amount() + "**")
                .collect(Collectors.joining("\n\n"));
    }

    /** A post from before 1.20 (a plain embed), greyed out, with the expiry and the invitation to negotiate removed. */
    static MessageEmbed closed(MessageEmbed original, String title, String reason) {
        EmbedBuilder embed = new EmbedBuilder(original).setColor(Embeds.MUTED).setTitle(title).clearFields();
        for (MessageEmbed.Field field : original.getFields()) {
            if (!EXPIRES_FIELD.equals(field.getName()) && !NEGOTIATE_FIELD.equals(field.getName())) {
                embed.addField(field);
            }
        }
        // Only the first line ("Posted by …") is kept, so the post can be closed again with a new note.
        String description = original.getDescription() == null ? "" : original.getDescription().split("\n", 2)[0] + "\n";
        return embed.setDescription(description + "**" + reason + "**").build();
    }
}
