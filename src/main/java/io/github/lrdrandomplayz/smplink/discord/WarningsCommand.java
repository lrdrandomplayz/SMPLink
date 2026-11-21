package io.github.lrdrandomplayz.smplink.discord;

import java.util.ArrayList;
import java.util.List;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.Outcome;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.Warning;
import io.github.lrdrandomplayz.smplink.http.SyncBridge;
import io.github.lrdrandomplayz.smplink.service.PlayerService;

/** /warnings: every open activity-check warning with progress, plus the last two weeks' outcomes. */
final class WarningsCommand {

    private static final long RECENT_MS = 14L * 24 * 60 * 60 * 1000;

    private final PlayerService players;
    private final TrackingRepository tracking;
    private final ActivityCheck activityCheck;
    private final Strikes strikes;

    WarningsCommand(PlayerService players, TrackingRepository tracking, ActivityCheck activityCheck, Strikes strikes) {
        this.strikes = strikes;
        this.players = players;
        this.tracking = tracking;
        this.activityCheck = activityCheck;
    }

    static SlashCommandData definition() {
        return Commands.slash("warnings", "Open activity-check warnings and recent outcomes (whitelist staff only)");
    }

    /** Runs on a worker thread after an ephemeral defer. */
    void handle(InteractionHook hook) throws Exception {
        long now = System.currentTimeMillis();
        java.util.Set<String> originals = players.links().stream().filter(io.github.lrdrandomplayz.smplink.db.LinkRepository.Link::original)
                .map(io.github.lrdrandomplayz.smplink.db.LinkRepository.Link::discordId).collect(java.util.stream.Collectors.toSet());

        List<Warning> open = SyncBridge.await(tracking.pendingWarnings(), PlayerService.DB_TIMEOUT_MS);
        List<String> openLines = new ArrayList<>();
        for (Warning warning : open) {
            long played = players.secondsPlayedBetween(warning.uuid(), warning.windowStart(), Math.min(now, warning.deadline()));
            long minSeconds = activityCheck.minSeconds(originals.contains(warning.discordId()));
            long left = Math.max(0, minSeconds - played);
            int current = strikes.weighted(strikes.active(warning.discordId()));
            int limit = strikes.limit(hook.getJDA(), warning.discordId());
            openLines.add("**" + warning.ign() + "** · <@" + warning.discordId() + "> · " + Glyph.TIME + " <t:" + warning.deadline() / 1000
                    + ":R>" + (current > 0 ? " · " + Glyph.ALERT + " " + current + "/" + limit : "") + "\n"
                    + Embeds.quotaBar(played, minSeconds, 10) + " · "
                    + (left == 0 ? Glyph.YES + " reached" : "**" + Embeds.formatPlaytime(left) + " left**"));
        }

        List<Outcome> recent = SyncBridge.await(tracking.resolvedSince(now - RECENT_MS), PlayerService.DB_TIMEOUT_MS);
        List<String> recentLines = recent.stream()
                .map(outcome -> icon(outcome.status()) + " <@" + outcome.discordId() + "> · `" + outcome.ign() + "` · "
                        + outcome.status() + " <t:" + outcome.resolvedAt() / 1000 + ":R>")
                .toList();

        long passed = recent.stream().filter(o -> o.status().equals("passed")).count();
        long removed = recent.stream().filter(o -> o.status().equals("removed")).count();
        long struck = recent.stream().filter(o -> o.status().equals("struck")).count();
        EmbedBuilder embed = Embeds.base(open.isEmpty() ? Embeds.SUCCESS : Embeds.WARNING)
                .setTitle(Glyph.ACTIVITY + " Activity warnings")
                .setDescription("**" + open.size() + "** open, soonest deadline first. Quota: " + Embeds.formatHours(activityCheck.minSeconds(false))
                        + (activityCheck.minSeconds(true) > 0 ? " (Original Supporters " + Embeds.formatHours(activityCheck.minSeconds(true)) + ")" : "")
                        + " between the week before the warning and the deadline.\n"
                        + (activityCheck.pause().isPaused() ? Glyph.TIME + " **Activity checks are paused**"
                        + (activityCheck.pause().until() > 0 ? " until <t:" + activityCheck.pause().until() / 1000 + ":F>" : "") + ".\n" : "")
                        + "Missing the deadline gives a strike; reaching the strike limit (strikes last "
                        + strikes.expiryText() + ") is a permanent removal.\n"
                        + "Last 14 days: " + Glyph.YES + " " + passed + " passed · " + Glyph.ALERT + " " + struck + " struck · " + Glyph.BANNED + " " + removed + " removed.");
        if (openLines.isEmpty()) {
            embed.addField("Open", "Nobody has an open warning.", false);
        }
        Embeds.addList(embed, "Open (" + open.size() + ")", openLines);
        Embeds.addList(embed, "Resolved in the last 14 days", recentLines);
        hook.editOriginalEmbeds(embed.build()).setAllowedMentions(List.of()).queue();
    }

    private static String icon(String status) {
        return switch (status) {
            case "passed" -> Glyph.YES.toString();
            case "removed" -> Glyph.BANNED.toString();
            case "struck" -> Glyph.ALERT.toString();
            case "failed" -> Glyph.WARN.toString();
            default -> "▫️";
        };
    }
}
