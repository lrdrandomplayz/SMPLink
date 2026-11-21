package io.github.lrdrandomplayz.smplink.discord;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import io.github.lrdrandomplayz.smplink.PluginSettings;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.Milestone;
import io.github.lrdrandomplayz.smplink.http.SyncBridge;
import io.github.lrdrandomplayz.smplink.service.PlayerService;
import io.github.lrdrandomplayz.smplink.service.PlayerService.Playtime;

/**
 * Posts "<player> has reached N hours!" when a linked player's all-time playtime passes a milestone.
 * The first time a player is checked, milestones they already passed are recorded without an
 * announcement, so installing this (or linking a veteran) doesn't flood the channel.
 */
final class MilestoneAnnouncer {

    private final PluginSettings settings;
    private final PlayerService players;
    private final TrackingRepository tracking;
    private final Logger logger;

    MilestoneAnnouncer(PluginSettings settings, PlayerService players, TrackingRepository tracking, Logger logger) {
        this.settings = settings;
        this.players = players;
        this.tracking = tracking;
        this.logger = logger;
    }

    boolean enabled() {
        return !settings.milestoneChannelId().isEmpty() && !settings.milestoneHours().isEmpty();
    }

    synchronized void check(JDA jda) throws Exception {
        if (!enabled() || jda == null) {
            return;
        }
        List<Playtime> ranking = players.allTimeRanking();
        Set<UUID> seen = SyncBridge.await(tracking.milestoneSeen(), PlayerService.DB_TIMEOUT_MS);
        Map<UUID, Set<Integer>> reached = SyncBridge.await(tracking.milestonesReached(), PlayerService.DB_TIMEOUT_MS);

        long now = System.currentTimeMillis();
        List<Milestone> silent = new ArrayList<>();
        List<UUID> newlySeen = new ArrayList<>();
        List<Playtime> toAnnounceFor = new ArrayList<>();
        List<Integer> toAnnounceHours = new ArrayList<>();
        for (Playtime player : ranking) {
            Set<Integer> have = reached.getOrDefault(player.uuid(), Set.of());
            boolean firstLook = !seen.contains(player.uuid());
            for (int hours : settings.milestoneHours()) {
                if (player.playtimeSeconds() < hours * 3600L || have.contains(hours)) {
                    continue;
                }
                if (firstLook) {
                    silent.add(new Milestone(player.uuid(), hours, now));
                } else {
                    toAnnounceFor.add(player);
                    toAnnounceHours.add(hours);
                }
            }
            if (firstLook) {
                newlySeen.add(player.uuid());
            }
        }
        if (!silent.isEmpty() || !newlySeen.isEmpty()) {
            SyncBridge.await(tracking.recordMilestones(silent, false, newlySeen), PlayerService.DB_TIMEOUT_MS);
        }
        if (toAnnounceFor.isEmpty()) {
            return;
        }

        GuildMessageChannel channel = Channels.find(jda, settings.milestoneChannelId(), "milestones.channel-id", logger);
        if (channel == null) {
            return;
        }
        for (int i = 0; i < toAnnounceFor.size(); i++) {
            Playtime player = toAnnounceFor.get(i);
            int hours = toAnnounceHours.get(i);
            String message = settings.milestoneMessage()
                    .replace("{discord}", "<@" + player.discordId() + ">")
                    .replace("{ign}", player.name() == null ? "Unknown" : player.name())
                    .replace("{hours}", String.valueOf(hours));
            try {
                channel.sendMessage(message).setAllowedMentions(EnumSet.of(Message.MentionType.USER)).complete();
                // Recorded only once posted, so a failed post is retried at the next check.
                SyncBridge.await(tracking.recordMilestones(List.of(new Milestone(player.uuid(), hours, now)), true, List.of()),
                        PlayerService.DB_TIMEOUT_MS);
                logger.info("[milestones] " + player.name() + " (discord=" + player.discordId() + ") reached " + hours + " h");
            } catch (RuntimeException e) {
                logger.warning("[milestones] could not announce " + player.name() + " reaching " + hours + " h: " + e.getMessage());
            }
        }
    }
}
