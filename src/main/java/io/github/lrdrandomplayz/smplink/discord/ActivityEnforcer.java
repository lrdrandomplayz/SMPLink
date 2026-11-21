package io.github.lrdrandomplayz.smplink.discord;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import io.github.lrdrandomplayz.smplink.PluginSettings;
import io.github.lrdrandomplayz.smplink.db.LinkRepository.Link;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.Warning;
import io.github.lrdrandomplayz.smplink.http.SyncBridge;
import io.github.lrdrandomplayz.smplink.service.PlayerService;

/**
 * Follows up activity-check warnings every few minutes: a player who reaches the quota gets a
 * congratulations DM straight away; one still short at the deadline gets a strike (see
 * {@link StrikeService}, which also handles the strike that reaches their limit). Runs late if the
 * server was off at the deadline, counting only playtime up to the deadline.
 */
final class ActivityEnforcer {

    private final PluginSettings settings;
    private final PlayerService players;
    private final TrackingRepository tracking;
    private final Strikes strikes;
    private final StrikeService strikeService;
    private final Logger logger;
    private final ActivityCheck activityCheck;

    ActivityEnforcer(PluginSettings settings, PlayerService players, TrackingRepository tracking, Strikes strikes,
                     StrikeService strikeService, ActivityCheck activityCheck, Logger logger) {
        this.activityCheck = activityCheck;
        this.settings = settings;
        this.players = players;
        this.tracking = tracking;
        this.strikes = strikes;
        this.strikeService = strikeService;
        this.logger = logger;
    }

    synchronized void check(JDA jda) throws Exception {
        if (activityCheck.pause().isPaused()) {
            return; // no congratulations, strikes or removals while paused
        }
        List<Warning> pending = SyncBridge.await(tracking.pendingWarnings(), PlayerService.DB_TIMEOUT_MS);
        if (pending.isEmpty() || jda == null) {
            return;
        }
        java.util.Map<String, Link> linked = players.links().stream()
                .collect(Collectors.toMap(Link::discordId, l -> l, (a, b) -> a));
        long now = System.currentTimeMillis();
        List<String> passed = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        for (Warning warning : pending) {
            String who = "<@" + warning.discordId() + "> · `" + warning.ign() + "`";
            Link link = linked.get(warning.discordId());
            long minSeconds = link == null ? 0 : activityCheck.minSeconds(link.original());
            String required = Embeds.formatHours(minSeconds);
            if (link == null || minSeconds <= 0 || strikes.exemptFromActivity(jda, warning.discordId())) {
                // Removed, relinked by hand or given an exempt role since the warning; nothing left to enforce.
                SyncBridge.await(tracking.resolveWarning(warning.id(), "cancelled"), PlayerService.DB_TIMEOUT_MS);
                continue;
            }
            long played = players.secondsPlayedBetween(warning.uuid(), warning.windowStart(), Math.min(now, warning.deadline()));
            if (played >= minSeconds) {
                String failure = ActivityCheck.sendDm(jda, warning.discordId(), settings.activityPassedMessage()
                        .replace("{player}", warning.ign()).replace("{required}", required));
                SyncBridge.await(tracking.resolveWarning(warning.id(), "passed"), PlayerService.DB_TIMEOUT_MS);
                passed.add(who + " · " + Embeds.formatPlaytime(played) + (failure == null ? "" : " · DM failed (" + failure + ")"));
                logger.info("[activity] discord=" + warning.discordId() + " (" + warning.ign() + ") passed with "
                        + played / 60 + " minutes");
                continue;
            }
            if (now < warning.deadline()) {
                continue;
            }

            // Deadline missed: a strike, dated at the deadline so it expires the same time whenever it's processed.
            List<MessageEmbed.Field> evidence = List.of(
                    new MessageEmbed.Field(Glyph.TIME + " Played", Embeds.formatPlaytime(played) + " of " + required + "\n"
                            + Embeds.quotaBar(played, minSeconds, 10), true),
                    new MessageEmbed.Field(Glyph.TIME + " Deadline", "<t:" + warning.deadline() / 1000 + ":f>", true));
            StrikeService.Given given = strikeService.give(jda,
                    new StrikeService.Target(warning.discordId(), warning.uuid(), warning.ign()), "activity",
                    "Missed the activity-check deadline (" + Embeds.formatPlaytime(played) + " of " + required + ")",
                    "the activity check", warning.id(), warning.deadline(),
                    settings.activityStrikeMessage().replace("{required}", required),
                    settings.activityRemovedMessage().replace("{required}", required), evidence);
            if (given.problem() != null) {
                SyncBridge.await(tracking.resolveWarning(warning.id(), "failed"), PlayerService.DB_TIMEOUT_MS);
                problems.add(who + " · " + given.problem());
            } else {
                SyncBridge.await(tracking.resolveWarning(warning.id(), given.removed() ? "removed" : "struck"),
                        PlayerService.DB_TIMEOUT_MS);
            }
        }

        // Strikes and removals get their own message each; this summarises the rest.
        if (!passed.isEmpty() || !problems.isEmpty()) {
            post(jda, passed, problems);
        }
    }

    private void post(JDA jda, List<String> passed, List<String> problems) {
        GuildMessageChannel channel = Channels.find(jda, settings.activityLogChannelId(), "activity-check.log-channel-id", logger);
        if (channel == null) {
            return;
        }
        EmbedBuilder embed = Embeds.base(problems.isEmpty() ? Embeds.SUCCESS : Embeds.DANGER)
                .setTitle(Glyph.ACTIVITY + " Activity check follow-up")
                .setFooter("Each member's quota counts from the week before their warning up to the deadline");
        Embeds.addList(embed, Glyph.YES + " Reached the quota (" + passed.size() + ")", passed);
        Embeds.addList(embed, Glyph.WARN + " Needs an admin (" + problems.size() + ")", problems);
        channel.sendMessageEmbeds(embed.build()).setAllowedMentions(List.of())
                .queue(null, error -> logger.warning("[activity] could not post to the log channel: " + error.getMessage()));
    }
}
