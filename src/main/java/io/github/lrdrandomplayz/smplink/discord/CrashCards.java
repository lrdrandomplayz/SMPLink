package io.github.lrdrandomplayz.smplink.discord;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import io.github.lrdrandomplayz.smplink.crash.CrashCase;
import io.github.lrdrandomplayz.smplink.crash.CrashCase.MissingItem;
import io.github.lrdrandomplayz.smplink.crash.CrashRecovery;
import io.github.lrdrandomplayz.smplink.crash.CrashRecovery.Outcome;

/**
 * The staff card for a crash rollback (Components V2): who lost how much, what's missing, where they really were,
 * dupe warnings, and Give items / Teleport back / Skip buttons (ids {@code crash:give|tp|skip:<caseId>}). The card is
 * redrawn from the stored {@link CrashCase} after each action, with the used buttons removed. Route button events
 * whose id passes {@link #isComponent} to {@link #onButton}.
 */
public final class CrashCards {

    private static final String PREFIX = "crash:";
    private static final String GIVE = PREFIX + "give:";
    private static final String TP = PREFIX + "tp:";
    private static final String SKIP = PREFIX + "skip:";
    private static final int MAX_ITEM_LINES = 15;

    private final CrashRecovery recovery;
    private final Predicate<Member> isAdmin;
    private final Consumer<Runnable> mainThread;
    private final Logger logger;

    /**
     * @param isAdmin    who may press the buttons
     * @param mainThread runs a Runnable on the Bukkit main thread (e.g. {@code r -> Bukkit.getScheduler().runTask(plugin, r)})
     */
    public CrashCards(CrashRecovery recovery, Predicate<Member> isAdmin, Consumer<Runnable> mainThread, Logger logger) {
        this.recovery = recovery;
        this.isAdmin = isAdmin;
        this.mainThread = mainThread;
        this.logger = logger;
    }

    public static boolean isComponent(String id) {
        return id.startsWith(PREFIX);
    }

    // ---------------------------------------------------------------- posting

    /** Posts the case's card to the staff channel (no-op when the channel isn't set or visible). */
    public static void post(JDA jda, String channelId, CrashCase c, Logger logger) {
        GuildMessageChannel channel = Channels.find(jda, channelId, "crash-recovery.staff-channel-id", logger);
        if (channel == null) {
            return;
        }
        channel.sendMessageComponents(render(c)).useComponentsV2().setAllowedMentions(List.of())
                .queue(null, e -> logger.warning("[crash] couldn't post the card for " + c.name() + ": " + e.getMessage()));
    }

    /** The short public message after a crash. */
    public static void postCrashNotice(JDA jda, String channelId, long crashedAt, long backAt, int playersOnline,
                                       Logger logger) {
        GuildMessageChannel channel = Channels.find(jda, channelId, "crash-recovery.announce-channel-id", logger);
        if (channel == null) {
            return;
        }
        channel.sendMessage(Glyph.ALERT + " The server crashed at <t:" + crashedAt / 1000 + ":t> and was back <t:"
                        + backAt / 1000 + ":R>. Some players lost a few minutes; staff are restoring items.")
                .setAllowedMentions(List.of())
                .queue(null, e -> logger.warning("[crash] couldn't post the crash notice: " + e.getMessage()));
    }

    // ---------------------------------------------------------------- the card

    static Container render(CrashCase c) {
        List<ContainerChildComponent> parts = new ArrayList<>();
        String rolled = mmss(c.rolledBackMillis());
        String head = "## " + Glyph.RESTART + " Crash rollback · " + c.name();
        String sub = "-# Rolled back **" + rolled + "**" + (c.saveUncertain() ? " (best guess)" : "")
                + " · saved <t:" + c.saveTime() / 1000 + ":T> → crashed <t:" + c.crashTime() / 1000 + ":T>";
        parts.add(Section.of(Thumbnail.fromUrl("https://mc-heads.net/avatar/" + c.name() + "/128"),
                TextDisplay.of(head + "\n" + sub)));
        parts.add(Separator.createDivider(Separator.Spacing.SMALL));

        StringBuilder items = new StringBuilder("### " + Glyph.INVENTORY + " Missing items\n");
        if (c.missing().isEmpty()) {
            items.append("Nothing missing.");
        }
        int shown = 0;
        for (MissingItem m : c.missing()) {
            if (shown++ == MAX_ITEM_LINES) {
                items.append("...and ").append(c.missing().size() - MAX_ITEM_LINES).append(" more\n");
                break;
            }
            items.append(m.amount()).append("× ").append(m.name()).append('\n');
        }
        parts.add(TextDisplay.of(items.toString().stripTrailing()));

        StringBuilder where = new StringBuilder("### " + Glyph.LOCATION + " Position\n");
        if (c.real() != null) {
            where.append("Was at **").append(coords(c.real())).append("**");
        }
        if (c.join() != null) {
            where.append("\nRejoined at ").append(coords(c.join()));
            if (c.moved() < 1e8) {
                where.append(" (").append(Math.round(c.moved())).append(" blocks away)");
            }
        }
        parts.add(Separator.createDivider(Separator.Spacing.SMALL));
        parts.add(TextDisplay.of(where.toString()));

        if (!c.dupeWarnings().isEmpty()) {
            StringBuilder warn = new StringBuilder("### " + Glyph.WARN + " Possible dupes\n");
            for (String line : c.dupeWarnings()) {
                warn.append(Glyph.WARN).append(' ').append(line).append('\n');
            }
            parts.add(Separator.createDivider(Separator.Spacing.SMALL));
            parts.add(TextDisplay.of(warn.toString().stripTrailing()));
        }
        if (!c.log().isEmpty()) {
            parts.add(Separator.createDivider(Separator.Spacing.SMALL));
            parts.add(TextDisplay.of("### Outcome\n" + c.log()));
        }

        List<Button> buttons = new ArrayList<>();
        if (c.open()) {
            if (!c.gave() && !c.givePending() && !c.missing().isEmpty()) {
                buttons.add(Button.success(GIVE + c.id(), "Give items").withEmoji(Glyph.GIFT.emoji()));
            }
            if (!c.tped() && c.real() != null) {
                buttons.add(Button.primary(TP + c.id(), "Teleport back").withEmoji(Glyph.LOCATION.emoji()));
            }
            if (!buttons.isEmpty()) {
                buttons.add(Button.secondary(SKIP + c.id(), "Skip").withEmoji(Glyph.NO.emoji()));
            }
        }
        if (!buttons.isEmpty()) {
            parts.add(Separator.createDivider(Separator.Spacing.SMALL));
            parts.add(ActionRow.of(buttons));
        }
        int accent = c.status().equals("skipped") ? Embeds.MUTED
                : buttons.isEmpty() ? Embeds.SUCCESS : c.dupeWarnings().isEmpty() ? Embeds.WARNING : Embeds.DANGER;
        return Container.of(parts).withAccentColor(accent);
    }

    static String mmss(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    private static String coords(CrashCase.Pos p) {
        return p.world() + " " + Math.round(p.x()) + " " + Math.round(p.y()) + " " + Math.round(p.z());
    }

    // ---------------------------------------------------------------- buttons

    /** Admin-only. Runs the Bukkit action on the main thread, then redraws the card. */
    public void onButton(ButtonInteractionEvent event, ExecutorService pool) {
        if (!isAdmin.test(event.getMember())) {
            event.reply("Only admins can restore crash rollbacks.").setEphemeral(true).queue();
            return;
        }
        String id = event.getComponentId();
        String action;
        if (id.startsWith(GIVE)) {
            action = "give";
        } else if (id.startsWith(TP)) {
            action = "tp";
        } else if (id.startsWith(SKIP)) {
            action = "skip";
        } else {
            return;
        }
        long caseId;
        try {
            caseId = Long.parseLong(id.substring(id.lastIndexOf(':') + 1));
        } catch (NumberFormatException e) {
            event.reply("That card is out of date.").setEphemeral(true).queue();
            return;
        }
        event.deferEdit().queue(hook -> pool.execute(() -> run(event, action, caseId)), e -> { });
    }

    private void run(ButtonInteractionEvent event, String action, long caseId) {
        try {
            Optional<CrashCase> loaded = recovery.load(caseId);
            if (loaded.isEmpty()) {
                event.getHook().sendMessage("Couldn't find that case any more.").setEphemeral(true).queue();
                return;
            }
            CrashCase c = loaded.get();
            if (!c.open()) {
                redraw(event, c);
                return;
            }
            CompletableFuture<Outcome> result = new CompletableFuture<>();
            mainThread.accept(() -> {
                try {
                    result.complete(switch (action) {
                        case "give" -> recovery.give(c);
                        case "tp" -> recovery.teleportBack(c);
                        default -> recovery.skip(c);
                    });
                } catch (RuntimeException e) {
                    result.completeExceptionally(e);
                }
            });
            Outcome outcome = result.get(15, TimeUnit.SECONDS);
            CrashCase updated = outcome.updated();
            if (updated == c) { // nothing changed (offline, already done): say why, keep the buttons
                event.getHook().sendMessage(outcome.message()).setEphemeral(true).queue();
                return;
            }
            // Skip closes everything; otherwise the card stays open until both actions are used.
            logger.info("[crash] " + event.getUser().getName() + " " + action + " on case " + caseId + ": " + outcome.message());
            redraw(event, updated);
        } catch (Exception e) {
            logger.log(Level.WARNING, "[crash] button " + action + " on case " + caseId + " failed", e);
            event.getHook().sendMessage("Something went wrong: " + e.getMessage()).setEphemeral(true).queue();
        }
    }

    private void redraw(ButtonInteractionEvent event, CrashCase c) {
        List<MessageTopLevelComponent> components = List.of(render(c));
        event.getMessage().editMessageComponents(components).useComponentsV2().setAllowedMentions(List.of())
                .queue(null, e -> logger.warning("[crash] couldn't update the card for " + c.name() + ": " + e.getMessage()));
    }
}
