package io.github.lrdrandomplayz.smplink.discord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu.SelectTarget;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.interactions.InteractionHook;
import io.github.lrdrandomplayz.smplink.db.LinkRepository.Link;
import io.github.lrdrandomplayz.smplink.db.TradeRepository;
import io.github.lrdrandomplayz.smplink.db.TradeRepository.Interest;
import io.github.lrdrandomplayz.smplink.db.TradeRepository.Rep;
import io.github.lrdrandomplayz.smplink.db.TradeRepository.Trade;
import io.github.lrdrandomplayz.smplink.http.SyncBridge;
import io.github.lrdrandomplayz.smplink.service.PlayerService;

/**
 * Everything after a trade is posted:
 * <ol>
 * <li>Members press "I'm interested"; the poster gets a DM for each and sees them listed later.</li>
 * <li>The poster presses Mark traded and picks who they traded with (from the interested list, or anyone).</li>
 * <li>That partner is asked by DM whether the trade really happened. Nobody can be rated on the poster's
 * word alone.</li>
 * <li>Once they say yes, both traders get a DM to vouch for the other or report a scam. Ratings add up to
 * the rep on /profile. Nobody reviews the reports; they count straight away.</li>
 * </ol>
 */
final class TradeReviews {

    private static final String PARTNER_PREFIX = "trade:partner:";
    private static final String PICK_PREFIX = "trade:pick:";
    private static final String SOLO_PREFIX = "trade:solo:";
    private static final String CONFIRM_PREFIX = "trade:confirm:";
    private static final String RATE_PREFIX = "trade:rate:";
    // "trade:mmused:<message>:<partner>:y|n", the poster's answer to "did the middleman handle this trade?".
    private static final String MM_USED_PREFIX = "trade:mmused:";

    private final TradeBoard board;
    private final TradeRepository trades;
    private final Logger logger;
    private final RateLimiter interestLimiter = new RateLimiter(10, 60_000);

    TradeReviews(TradeBoard board, TradeRepository trades, Logger logger) {
        this.board = board;
        this.trades = trades;
        this.logger = logger;
    }

    Rep middlemanRep(String discordId) throws Exception {
        return SyncBridge.await(trades.middlemanRep(discordId), PlayerService.DB_TIMEOUT_MS);
    }

    Rep rep(String discordId) throws Exception {
        return SyncBridge.await(trades.rep(discordId), PlayerService.DB_TIMEOUT_MS);
    }

    /** Every trade: button; returns false for IDs it doesn't handle. */
    boolean onButton(ButtonInteractionEvent event, ExecutorService pool) {
        String id = event.getComponentId();
        if (id.startsWith(TradeBoard.INTEREST_PREFIX)) {
            onInterest(event, pool);
        } else if (id.startsWith(TradeBoard.MIDDLEMAN_PREFIX)) {
            board.onMiddleman(event, pool);
        } else if (id.startsWith(TradeBoard.CLAIM_PREFIX)) {
            board.onClaim(event, pool);
        } else if (id.startsWith(TradeBoard.CANCEL_MM_PREFIX)) {
            board.onCancelMiddleman(event, pool);
        } else if (id.startsWith(MM_USED_PREFIX)) {
            String[] parts = id.substring(MM_USED_PREFIX.length()).split(":");
            if (parts.length != 3) {
                return false;
            }
            event.deferEdit().queue(hook -> pool.execute(() -> {
                try {
                    User partner = event.getJDA().retrieveUserById(parts[1]).complete();
                    complete(hook, event.getJDA(), event.getUser(), parts[0], partner, parts[2].equals("y"));
                } catch (RuntimeException e) {
                    hook.editOriginal("I couldn't find that member on Discord any more.").setComponents().queue();
                }
            }));
        } else if (id.startsWith(TradeBoard.DONE_PREFIX)) {
            askForPartner(event);
        } else if (id.startsWith(TradeBoard.DELETE_PREFIX)) {
            board.onRemove(event, pool);
        } else if (id.startsWith(SOLO_PREFIX)) {
            String messageId = id.substring(SOLO_PREFIX.length());
            event.deferEdit().queue(hook -> pool.execute(() -> complete(hook, event.getJDA(), event.getUser(), messageId, null, null)));
        } else if (id.startsWith(CONFIRM_PREFIX)) {
            String[] parts = id.substring(CONFIRM_PREFIX.length()).split(":");
            if (parts.length != 2) {
                return false;
            }
            event.deferEdit().queue(hook -> pool.execute(() -> confirm(hook, event.getJDA(), event.getUser(), parts[0], parts[1].equals("y"))));
        } else if (id.startsWith(RATE_PREFIX)) {
            String[] parts = id.substring(RATE_PREFIX.length()).split(":");
            if (parts.length != 2) {
                return false;
            }
            // v / s rate the other trader; mv / ms rate the trade's middleman.
            boolean middleman = parts[1].startsWith("m");
            boolean vouch = parts[1].endsWith("v");
            event.deferEdit().queue(hook -> pool.execute(() -> rate(hook, event.getUser(), parts[0], middleman, vouch)));
        } else {
            return false;
        }
        return true;
    }

    // --- 1. I'm interested --------------------------------------------------------------------------------

    private void onInterest(ButtonInteractionEvent event, ExecutorService pool) {
        String owner = event.getComponentId().substring(TradeBoard.INTEREST_PREFIX.length());
        User user = event.getUser();
        if (owner.equals(user.getId())) {
            event.reply("That's your own trade. When it's done, press **Mark traded**.").setEphemeral(true).queue();
            return;
        }
        long waitMs = interestLimiter.take(user.getId());
        if (waitMs > 0) {
            event.reply("You're doing that too quickly. Try again in " + ((waitMs + 999) / 1000) + " s.").setEphemeral(true).queue();
            return;
        }
        Message post = event.getMessage();
        event.deferReply(true).queue(hook -> pool.execute(() -> {
            try {
                Optional<Link> link = board.linkOf(user.getId());
                if (link.isEmpty()) {
                    hook.editOriginal("Only linked members can trade. Links come from the server's sign-up sheet.").queue();
                    return;
                }
                Optional<Trade> trade = SyncBridge.await(trades.find(post.getId()), PlayerService.DB_TIMEOUT_MS);
                if (trade.isEmpty() || !trade.get().status().equals("open")) {
                    hook.editOriginal("This trade is closed.").queue();
                    return;
                }
                boolean added = SyncBridge.await(trades.addInterest(post.getId(), user.getId(), link.get().name(),
                        System.currentTimeMillis()), PlayerService.DB_TIMEOUT_MS);
                if (!added) {
                    // Already interested: point them back to their chat, or open one if it's gone.
                    Optional<Interest> mine = SyncBridge.await(trades.interested(post.getId()), PlayerService.DB_TIMEOUT_MS).stream()
                            .filter(i -> i.discordId().equals(user.getId())).findFirst();
                    if (mine.isPresent() && mine.get().threadId() != null && event.getJDA().getThreadChannelById(mine.get().threadId()) != null) {
                        hook.editOriginal("You've already said you're interested. Your chat with <@" + owner + ">: <#"
                                + mine.get().threadId() + ">").setAllowedMentions(List.of()).queue();
                        return;
                    }
                } else {
                    try {
                        board.refreshPost(post, trade.get(), null, null);
                    } catch (Exception e) {
                        logger.info("[trade] could not update the interest count on " + post.getId() + ": " + e.getMessage());
                    }
                }
                var thread = board.openNegotiation(post, trade.get(), user.getId(), link.get().name());
                if (thread != null) {
                    hook.editOriginal(Glyph.BELL + " A private chat with <@" + owner + "> is open: " + thread.getAsMention()
                            + "\nNegotiate there; the trade itself happens in game.").setAllowedMentions(List.of()).queue();
                } else {
                    // No thread (the bot can't create private threads here): fall back to letting the poster know by DM.
                    ActivityCheck.sendDm(event.getJDA(), owner, Glyph.NOTE + " **" + link.get().name() + "** (<@" + user.getId()
                            + ">) is interested in your trade: " + post.getJumpUrl());
                    hook.editOriginal(Glyph.BELL + " <@" + owner + "> has been told you're interested. DM them to negotiate.")
                            .setAllowedMentions(List.of()).queue();
                }
                logger.info("[trade] " + link.get().name() + " is interested in " + post.getId());
            } catch (Exception e) {
                logger.log(Level.WARNING, "Interest in trade " + post.getId() + " failed", e);
                hook.editOriginal("Something went wrong. Try again in a moment.").queue();
            }
        }));
    }

    // --- 2. Mark traded: pick the partner -----------------------------------------------------------------

    static boolean isPartnerMenu(String id) {
        return id.startsWith(PARTNER_PREFIX);
    }

    static boolean isPickMenu(String id) {
        return id.startsWith(PICK_PREFIX);
    }

    /** Mark traded on the post: the poster picks who they traded with, privately, interested members first. */
    private void askForPartner(ButtonInteractionEvent event) {
        String owner = event.getComponentId().substring(TradeBoard.DONE_PREFIX.length());
        if (!owner.equals(event.getUser().getId())) {
            event.reply("Only <@" + owner + "> can mark this trade as done.").setEphemeral(true).setAllowedMentions(List.of()).queue();
            return;
        }
        String messageId = event.getMessageId();
        List<Interest> interested;
        try {
            interested = SyncBridge.await(trades.interested(messageId), PlayerService.DB_TIMEOUT_MS);
        } catch (Exception e) {
            interested = List.of();
        }
        List<ActionRow> rows = new ArrayList<>();
        if (!interested.isEmpty()) {
            StringSelectMenu.Builder menu = StringSelectMenu.create(PICK_PREFIX + messageId).setPlaceholder("Someone who was interested");
            for (Interest interest : interested.stream().limit(25).toList()) {
                menu.addOptions(SelectOption.of(interest.ign(), interest.discordId())
                        .withDescription("Interested " + ago(interest.at())).withEmoji(Glyph.BELL.emoji()));
            }
            rows.add(ActionRow.of(menu.build()));
        }
        rows.add(ActionRow.of(EntitySelectMenu.create(PARTNER_PREFIX + messageId, SelectTarget.USER)
                .setPlaceholder(interested.isEmpty() ? "Pick the member you traded with" : "Someone else")
                .setRequiredRange(1, 1).build()));
        rows.add(ActionRow.of(Button.secondary(SOLO_PREFIX + messageId, "They're not on Discord, just close it")));
        event.reply(Glyph.TRADE + " **Who did you trade with?** They'll be asked to confirm; once they do, you'll both get a DM to vouch "
                        + "for each other or report a scam.")
                .setEphemeral(true).setComponents(rows).queue();
    }

    private static String ago(long at) {
        long minutes = Math.max(1, (System.currentTimeMillis() - at) / 60_000);
        return minutes < 60 ? minutes + " min ago" : minutes < 48 * 60 ? minutes / 60 + " h ago" : minutes / 1440 + " days ago";
    }

    void onPartnerPicked(EntitySelectInteractionEvent event, ExecutorService pool) {
        String messageId = event.getComponentId().substring(PARTNER_PREFIX.length());
        List<User> picked = event.getMentions().getUsers();
        if (picked.isEmpty()) {
            event.deferEdit().queue();
            return;
        }
        User partner = picked.get(0);
        event.deferEdit().queue(hook -> pool.execute(() -> complete(hook, event.getJDA(), event.getUser(), messageId, partner, null)));
    }

    void onInterestedPicked(StringSelectInteractionEvent event, ExecutorService pool) {
        String messageId = event.getComponentId().substring(PICK_PREFIX.length());
        if (event.getValues().isEmpty()) {
            event.deferEdit().queue();
            return;
        }
        String partnerId = event.getValues().get(0);
        event.deferEdit().queue(hook -> pool.execute(() -> {
            try {
                User partner = event.getJDA().retrieveUserById(partnerId).complete();
                complete(hook, event.getJDA(), event.getUser(), messageId, partner, null);
            } catch (RuntimeException e) {
                hook.editOriginal("I couldn't find that member on Discord any more. Pick someone else.").queue();
            }
        }));
    }

    /**
     * Closes the post as traded and, when a partner is given, asks them to confirm it. If a middleman
     * claimed the trade, the poster is first asked whether they really handled it ({@code middlemanUsed}
     * is null until they answer): only then can the middleman be rated and get the trade on their record.
     */
    private void complete(InteractionHook hook, JDA jda, User poster, String messageId, User partner, Boolean middlemanUsed) {
        try {
            Optional<Trade> found = SyncBridge.await(trades.find(messageId), PlayerService.DB_TIMEOUT_MS);
            if (found.isEmpty() || !found.get().discordId().equals(poster.getId())) {
                hook.editOriginal("I can't find this trade any more.").setComponents().queue();
                return;
            }
            Trade trade = found.get();
            if (!trade.status().equals("open")) {
                hook.editOriginal("This trade is already closed.").setComponents().queue();
                return;
            }
            Optional<Link> partnerLink = Optional.empty();
            if (partner != null) {
                if (partner.getId().equals(poster.getId()) || partner.isBot()) {
                    hook.editOriginal(Glyph.TRADE + " Pick the member you traded with, not yourself or a bot.").queue();
                    return;
                }
                partnerLink = board.linkOf(partner.getId());
                if (partnerLink.isEmpty()) {
                    hook.editOriginal(Glyph.TRADE + " <@" + partner.getId() + "> isn't linked to a Minecraft account, so they can't "
                            + "be rated. Pick someone else, or close it without a partner.").setAllowedMentions(List.of()).queue();
                    return;
                }
            }
            if (partner != null && trade.middlemanId() != null && middlemanUsed == null) {
                hook.editOriginal(Glyph.SHIELD + " **Did <@" + trade.middlemanId() + "> middleman this trade** with <@" + partner.getId() + ">?\n"
                                + "Only say yes if they handled it; they can then be vouched for or reported, and it counts on their record.")
                        .setComponents(ActionRow.of(
                                Button.success(MM_USED_PREFIX + messageId + ":" + partner.getId() + ":y", "Yes, they did").withEmoji(Glyph.SHIELD.emoji()),
                                Button.secondary(MM_USED_PREFIX + messageId + ":" + partner.getId() + ":n", "No, we didn't use them")))
                        .setAllowedMentions(List.of()).queue();
                return;
            }
            if (middlemanUsed != null) {
                SyncBridge.await(trades.setMiddlemanUsed(messageId, middlemanUsed), PlayerService.DB_TIMEOUT_MS);
                trade = SyncBridge.await(trades.find(messageId), PlayerService.DB_TIMEOUT_MS).orElse(trade);
            }
            if (!SyncBridge.await(trades.complete(messageId, partner == null ? null : partner.getId()), PlayerService.DB_TIMEOUT_MS)) {
                hook.editOriginal("This trade is already closed.").setComponents().queue();
                return;
            }
            board.archiveThreads(jda, messageId, "This trade was marked as traded"
                    + (partner == null ? "." : " with <@" + partner.getId() + ">."));
            String when = "<t:" + Instant.now().getEpochSecond() + ":R>";
            if (partner == null) {
                updatePost(jda, trade, "Traded " + when);
                logger.info("[trade] " + trade.ign() + "'s trade " + messageId + " marked traded with no partner");
                hook.editOriginal(Glyph.YES + " Marked as traded.").setComponents().queue();
                return;
            }
            updatePost(jda, trade, "Traded with <@" + partner.getId() + "> " + when + " · " + Glyph.TIME + " waiting for them to confirm");
            String failure = sendConfirmDm(jda, partner.getId(), poster.getId(), trade);
            logger.info("[trade] " + trade.ign() + "'s trade " + messageId + " marked traded with " + partnerLink.get().name()
                    + (failure == null ? ", asked them to confirm" : "; could not ask them to confirm: " + failure));
            hook.editOriginal(failure == null
                            ? Glyph.YES + " Marked as traded. <@" + partner.getId() + "> has been asked to confirm; once they do, you'll both get "
                            + "a DM to vouch for each other or report a scam."
                            : Glyph.YES + " Marked as traded, but I couldn't DM <@" + partner.getId() + "> (" + failure + "), so they can't confirm "
                            + "it and neither of you can be rated. Ask them to allow DMs from server members.")
                    .setComponents().setAllowedMentions(List.of()).queue();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Marking trade " + messageId + " as traded failed", e);
            hook.editOriginal("Something went wrong. The error has been logged in the server console.").setComponents().queue();
        }
    }

    // --- 3. The partner confirms --------------------------------------------------------------------------

    private String sendConfirmDm(JDA jda, String partnerId, String posterId, Trade trade) {
        return sendDm(jda, partnerId, Embeds.base(Embeds.BRAND)
                        .setTitle(Glyph.TRADE + " Did you trade with " + trade.ign() + "?")
                        .setDescription("<@" + posterId + "> (`" + trade.ign() + "`) says you traded with them:\n\n"
                                + Glyph.PACKAGE + " **They offered:** " + TradeBoard.summary(trade.offerItems(), trade.offering()) + "\n"
                                + Glyph.TARGET + " **For:** " + TradeBoard.summary(trade.wantItems(), trade.wanted()) + "\n\n"
                                + "If you did, press **Yes** and you'll both be able to vouch for each other or report a scam. "
                                + "If you didn't, press **No** and nobody can be rated for it.")
                        .setThumbnail(Embeds.skin("avatar", trade.ign()))
                        .build(),
                ActionRow.of(Button.success(CONFIRM_PREFIX + trade.messageId() + ":y", "Yes, we traded").withEmoji(Glyph.YES.emoji()),
                        Button.danger(CONFIRM_PREFIX + trade.messageId() + ":n", "No, we didn't").withEmoji(Glyph.NO.emoji())));
    }

    private void confirm(InteractionHook hook, JDA jda, User partner, String messageId, boolean happened) {
        try {
            Optional<Trade> found = SyncBridge.await(trades.find(messageId), PlayerService.DB_TIMEOUT_MS);
            if (found.isEmpty() || !partner.getId().equals(found.get().partnerId())) {
                hook.editOriginal("I can't find this trade any more.").setComponents().queue();
                return;
            }
            Trade trade = found.get();
            if (!SyncBridge.await(trades.confirm(messageId, happened), PlayerService.DB_TIMEOUT_MS)) {
                hook.editOriginal("You've already answered for this trade.").setComponents().queue();
                return;
            }
            Optional<Link> partnerLink = board.linkOf(partner.getId());
            String partnerName = partnerLink.map(Link::name).orElse(partner.getName());
            if (!happened) {
                updatePost(jda, trade, "Marked traded with <@" + partner.getId() + ">, who said the trade didn't happen");
                ActivityCheck.sendDm(jda, trade.discordId(), Glyph.NO + " **" + partnerName + "** said they didn't trade with you, so neither "
                        + "of you can be rated for that trade.");
                hook.editOriginal(Glyph.NO + " Got it: you didn't trade with " + trade.ign() + ", so nobody can be rated for it.").setComponents().queue();
                logger.info("[trade] " + partnerName + " said trade " + messageId + " with " + trade.ign() + " didn't happen");
                return;
            }
            updatePost(jda, trade, "Traded with <@" + partner.getId() + "> · " + Glyph.YES + " confirmed");
            List<ActionRow> rateRows = rateButtons(trade);
            String toPoster = sendDm(jda, trade.discordId(), ratingEmbed(partner.getId(), partnerName, trade), rateRows);
            hook.editOriginalEmbeds(ratingEmbed(trade.discordId(), trade.ign(), trade)).setContent(Glyph.YES + " **Thanks for confirming.**")
                    .setComponents(rateRows).queue();
            logger.info("[trade] " + partnerName + " confirmed trade " + messageId + " with " + trade.ign()
                    + (toPoster == null ? "" : "; could not DM the poster: " + toPoster));
        } catch (Exception e) {
            logger.log(Level.WARNING, "Confirming trade " + messageId + " failed", e);
            hook.editOriginal("Something went wrong. Try again in a moment.").queue();
        }
    }

    // --- 4. Vouch or report -------------------------------------------------------------------------------

    /** The trade's middleman, if one handled it (the poster said so) and they aren't one of the two traders. */
    private static String middlemanOf(Trade trade) {
        String middleman = trade.middlemanId();
        return middleman == null || !Boolean.TRUE.equals(trade.middlemanUsed()) || middleman.equals(trade.discordId())
                || middleman.equals(trade.partnerId()) ? null : middleman;
    }

    /** Vouch / Report for the other trader, and a second row for the middleman if there was one. */
    private static List<ActionRow> rateButtons(Trade trade) {
        String id = trade.messageId();
        List<ActionRow> rows = new ArrayList<>();
        rows.add(ActionRow.of(
                Button.success(RATE_PREFIX + id + ":v", "Vouch").withEmoji(Emoji.fromUnicode("👍")),
                Button.danger(RATE_PREFIX + id + ":s", "Report scam").withEmoji(Glyph.REPORT.emoji())));
        if (middlemanOf(trade) != null) {
            rows.add(ActionRow.of(
                    Button.success(RATE_PREFIX + id + ":mv", "Vouch middleman").withEmoji(Glyph.SHIELD.emoji()),
                    Button.danger(RATE_PREFIX + id + ":ms", "Report middleman").withEmoji(Glyph.REPORT.emoji())));
        }
        return rows;
    }

    static MessageEmbed ratingEmbed(String aboutId, String aboutIgn, Trade trade) {
        String middleman = middlemanOf(trade);
        return Embeds.base(Embeds.BRAND)
                .setTitle(Glyph.TRADE + " How did your trade with " + aboutIgn + " go?")
                .setDescription("You traded with <@" + aboutId + "> (`" + aboutIgn + "`)"
                        + (middleman == null ? "" : ", with <@" + middleman + "> as the middleman") + ".\n\n"
                        + Glyph.PACKAGE + " **Offered:** " + TradeBoard.summary(trade.offerItems(), trade.offering()) + "\n"
                        + Glyph.TARGET + " **For:** " + TradeBoard.summary(trade.wantItems(), trade.wanted()) + "\n\n"
                        + "👍 **Vouch** if it went well, or " + Glyph.REPORT + " **Report scam** if you were scammed. A report takes 1 "
                        + "rep off them. Rep shows on /profile, and you can change your answer later."
                        + (middleman == null ? "" : "\n" + Glyph.SHIELD + " Rate the middleman with the second row: their middleman rep is kept "
                        + "separately from trade rep."))
                .setThumbnail(Embeds.skin("avatar", aboutIgn))
                .build();
    }

    private void rate(InteractionHook hook, User rater, String messageId, boolean middleman, boolean vouch) {
        try {
            Optional<Trade> found = SyncBridge.await(trades.find(messageId), PlayerService.DB_TIMEOUT_MS);
            if (found.isEmpty() || found.get().partnerId() == null) {
                hook.editOriginal("I can't find this trade any more.").setComponents().queue();
                return;
            }
            Trade trade = found.get();
            if (!Boolean.TRUE.equals(trade.confirmed())) {
                // Only reachable with buttons from before confirmations existed.
                hook.editOriginal("This trade hasn't been confirmed by both traders, so it can't be rated.").setComponents().queue();
                return;
            }
            boolean isTrader = rater.getId().equals(trade.discordId()) || rater.getId().equals(trade.partnerId());
            String partner = rater.getId().equals(trade.discordId()) ? trade.partnerId() : trade.discordId();
            String target = middleman ? middlemanOf(trade) : partner;
            if (!isTrader || target == null) {
                return;
            }
            String kind = middleman ? "middleman" : "trade";
            SyncBridge.await(trades.rate(messageId, rater.getId(), target, kind, vouch, System.currentTimeMillis()),
                    PlayerService.DB_TIMEOUT_MS);
            logger.info("[trade] " + rater.getName() + " " + (vouch ? "vouched for" : "reported") + " "
                    + (middleman ? "middleman " : "") + target + " (trade " + messageId + ")");
            hook.editOriginal(ratingSummary(SyncBridge.await(trades.ratingsBy(messageId, rater.getId()), PlayerService.DB_TIMEOUT_MS),
                    partner, middlemanOf(trade))).setAllowedMentions(List.of()).queue();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Rating trade " + messageId + " failed", e);
            hook.editOriginal("Something went wrong. Try again in a moment.").queue();
        }
    }

    /** "Your ratings: 👍 vouched for @x · 🚩 reported middleman @y", so rating one never hides the other. */
    static String ratingSummary(java.util.Map<String, Boolean> ratings, String partner, String middleman) {
        List<String> parts = new ArrayList<>();
        Boolean trade = ratings.get("trade");
        if (trade != null) {
            parts.add(trade ? "👍 vouched for <@" + partner + ">" : Glyph.REPORT + " reported <@" + partner + "> as a scam");
        }
        Boolean mm = ratings.get("middleman");
        if (mm != null && middleman != null) {
            parts.add(mm ? Glyph.SHIELD + " vouched for middleman <@" + middleman + ">" : Glyph.REPORT + " reported middleman <@" + middleman + ">");
        }
        return "**Your ratings:** " + String.join(" · ", parts)
                + "\nA report takes 1 rep off them. Changed your mind? Press the other button.";
    }

    // --- helpers -------------------------------------------------------------------------------------------

    /** Greys out the post (again, if it's already closed) with {@code note} under "Posted by". */
    private void updatePost(JDA jda, Trade trade, String note) {
        GuildMessageChannel channel = jda.getChannelById(GuildMessageChannel.class, trade.channelId());
        if (channel == null) {
            return;
        }
        try {
            Message message = channel.retrieveMessageById(trade.messageId()).complete();
            board.refreshPost(message, trade, Glyph.YES + " Traded", note);
        } catch (ErrorResponseException e) {
            logger.info("[trade] could not update the post for " + trade.messageId() + ": " + e.getMeaning());
        } catch (Exception e) {
            logger.log(Level.WARNING, "[trade] could not update the post for " + trade.messageId(), e);
        }
    }

    /** Returns null on success, or a short reason the DM could not be delivered. */
    private static String sendDm(JDA jda, String toId, MessageEmbed embed, ActionRow buttons) {
        return sendDm(jda, toId, embed, List.of(buttons));
    }

    private static String sendDm(JDA jda, String toId, MessageEmbed embed, List<ActionRow> rows) {
        try {
            User user = jda.retrieveUserById(toId).complete();
            user.openPrivateChannel().complete().sendMessageEmbeds(embed).setComponents(rows).complete();
            return null;
        } catch (ErrorResponseException e) {
            return e.getErrorCode() == 50007 ? "DMs from the bot are closed" : e.getMeaning();
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }
}
