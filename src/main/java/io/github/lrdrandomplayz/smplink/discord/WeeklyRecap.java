package io.github.lrdrandomplayz.smplink.discord;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToLongFunction;
import java.util.logging.Logger;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import io.github.lrdrandomplayz.smplink.PluginSettings;
import io.github.lrdrandomplayz.smplink.db.LinkRepository.Link;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.Milestone;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.Snapshot;
import io.github.lrdrandomplayz.smplink.http.SyncBridge;
import io.github.lrdrandomplayz.smplink.service.PlayerService;
import io.github.lrdrandomplayz.smplink.stats.StatsReader.Counters;

/**
 * Weekly recap post. Playtime comes from recorded sessions; kills, mining and deaths are the difference
 * between this week's and last week's snapshot of the all-time vanilla counters, so those need one
 * earlier snapshot (taken at startup the first time) before they can appear.
 */
final class WeeklyRecap {

    static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;

    /** One line of a top list. */
    record Entry(Link link, long value) {
    }

    /** Everything the recap embed shows. {@code statsSince} is 0 when there's no earlier snapshot yet. */
    record Data(long weekStart, long weekEnd, long totalSeconds, int activePlayers, List<Entry> playtime,
                List<Entry> mobKills, List<Entry> blocksMined, List<Entry> playerKills, List<Entry> deaths,
                long statsSince, List<Milestone> milestones, Map<UUID, Link> linksByUuid, List<Link> newMembers) {
    }

    private final PluginSettings settings;
    private final PlayerService players;
    private final TrackingRepository tracking;
    private final Logger logger;

    WeeklyRecap(PluginSettings settings, PlayerService players, TrackingRepository tracking, Logger logger) {
        this.settings = settings;
        this.players = players;
        this.tracking = tracking;
        this.logger = logger;
    }

    boolean enabled() {
        return !settings.recapChannelId().isEmpty();
    }

    /** Takes the first stat snapshot if there is none, so next week's recap can show kills and mining. */
    void ensureBaseline() throws Exception {
        if (!enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (SyncBridge.await(tracking.snapshotBefore(now), PlayerService.DB_TIMEOUT_MS) == null) {
            List<Link> links = players.links();
            if (links.isEmpty()) {
                // A snapshot of nobody stores no rows, so it would be retaken anyway; wait for the first link.
                return;
            }
            SyncBridge.await(tracking.saveSnapshot(now, players.counters(links)), PlayerService.DB_TIMEOUT_MS);
            logger.info("[recap] took the first stat snapshot; kills and mining appear from next week's recap");
        }
    }

    void post(JDA jda) throws Exception {
        if (!enabled() || jda == null) {
            return;
        }
        GuildMessageChannel channel = Channels.find(jda, settings.recapChannelId(), "recap.channel-id", logger);
        if (channel == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long weekStart = now - WEEK_MS;

        List<Link> links = players.links();
        Map<UUID, Link> byUuid = new HashMap<>();
        links.forEach(link -> byUuid.put(link.uuid(), link));

        Map<UUID, Long> played = players.secondsPlayedSince(weekStart);
        long total = 0;
        int active = 0;
        for (Link link : links) {
            long seconds = played.getOrDefault(link.uuid(), 0L);
            total += seconds;
            if (seconds > 0) {
                active++;
            }
        }

        Map<UUID, Counters> current = players.counters(links);
        // Allows a little slack so a snapshot taken a few minutes short of 7 days ago still counts.
        Snapshot previous = SyncBridge.await(tracking.snapshotBefore(now - WEEK_MS + 60L * 60 * 1000), PlayerService.DB_TIMEOUT_MS);
        if (previous == null) {
            previous = SyncBridge.await(tracking.snapshotBefore(now), PlayerService.DB_TIMEOUT_MS);
        }
        SyncBridge.await(tracking.saveSnapshot(now, current), PlayerService.DB_TIMEOUT_MS);

        List<Entry> mobKills = List.of();
        List<Entry> blocksMined = List.of();
        List<Entry> playerKills = List.of();
        List<Entry> deaths = List.of();
        long statsSince = 0;
        if (previous != null) {
            statsSince = previous.takenAt();
            mobKills = top(links, current, previous.counters(), Counters::mobKills, 3);
            blocksMined = top(links, current, previous.counters(), Counters::blocksMined, 3);
            playerKills = top(links, current, previous.counters(), Counters::playerKills, 3);
            deaths = top(links, current, previous.counters(), Counters::deaths, 3);
        }

        List<Entry> playtime = links.stream()
                .map(link -> new Entry(link, played.getOrDefault(link.uuid(), 0L)))
                .filter(entry -> entry.value() > 0)
                .sorted(Comparator.comparingLong(Entry::value).reversed())
                .limit(5)
                .toList();
        List<Milestone> milestones = SyncBridge.await(tracking.milestonesSince(weekStart), PlayerService.DB_TIMEOUT_MS);
        List<Link> newMembers = links.stream()
                .filter(link -> !link.original() && link.linkedAt() >= weekStart)
                .toList();

        Data data = new Data(weekStart, now, total, active, playtime, mobKills, blocksMined, playerKills, deaths,
                statsSince, milestones, byUuid, newMembers);
        channel.sendMessageEmbeds(Embeds.weeklyRecap(data)).setAllowedMentions(List.of()).complete();
        logger.info("[recap] posted the weekly recap (" + active + " active players, " + total / 3600 + " h played)");
    }

    /** Biggest increases in one counter since the previous snapshot; players missing from it are skipped. */
    private static List<Entry> top(List<Link> links, Map<UUID, Counters> now, Map<UUID, Counters> before,
                                   ToLongFunction<Counters> counter, int limit) {
        return links.stream()
                .filter(link -> now.containsKey(link.uuid()) && before.containsKey(link.uuid()))
                .map(link -> new Entry(link, counter.applyAsLong(now.get(link.uuid())) - counter.applyAsLong(before.get(link.uuid()))))
                .filter(entry -> entry.value() > 0)
                .sorted(Comparator.comparingLong(Entry::value).reversed())
                .limit(limit)
                .toList();
    }
}
