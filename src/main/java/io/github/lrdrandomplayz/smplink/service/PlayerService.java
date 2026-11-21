package io.github.lrdrandomplayz.smplink.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import io.github.lrdrandomplayz.smplink.db.InventoryRepository;
import io.github.lrdrandomplayz.smplink.db.InventoryRepository.CachedInventory;
import io.github.lrdrandomplayz.smplink.db.LinkRepository;
import io.github.lrdrandomplayz.smplink.db.LinkRepository.Link;
import io.github.lrdrandomplayz.smplink.db.SessionRepository;
import io.github.lrdrandomplayz.smplink.http.ApiException;
import io.github.lrdrandomplayz.smplink.http.SyncBridge;
import io.github.lrdrandomplayz.smplink.inventory.InventoryCodec;
import io.github.lrdrandomplayz.smplink.inventory.InventorySnapshot;
import io.github.lrdrandomplayz.smplink.inventory.ItemView;
import io.github.lrdrandomplayz.smplink.stats.StatsReader;
import io.github.lrdrandomplayz.smplink.stats.StatsReader.Counters;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

/**
 * Player lookups shared by the Discord bot and the HTTP API. Every method blocks on the
 * database worker and the main thread, so call it from a background thread, never the main one.
 */
public final class PlayerService {

    public record Stats(UUID uuid, String name, boolean online, String world, double playtimeHours, long deaths,
                        long mobKills, long playerKills, long blocksMined, double distanceWalkedKm, Long firstJoin,
                        Long lastSeen) {
    }

    public record Inventory(UUID uuid, String name, boolean online, boolean stale, long updatedAt,
                            List<ItemView> inventory, List<ItemView> armor, List<ItemView> enderChest) {
    }

    public record Playtime(int rank, String discordId, UUID uuid, String name, boolean online, long playtimeSeconds) {
    }

    /** Leaderboard periods. Weekly and monthly are rolling windows ending now, measured from recorded sessions. */
    public enum Period {
        ALL_TIME("all", "All time", 0),
        MONTHLY("monthly", "Monthly", 30),
        WEEKLY("weekly", "Weekly", 7);

        public final String id;
        public final String label;
        public final int days;

        Period(String id, String label, int days) {
            this.id = id;
            this.label = label;
            this.days = days;
        }

        public static Period fromId(String id) {
            for (Period period : values()) {
                if (period.id.equals(id)) {
                    return period;
                }
            }
            return ALL_TIME;
        }
    }

    /** {@code trackingStarted} lets callers flag weekly/monthly totals that don't yet cover the whole window. */
    /** {@code windowStart} is when a weekly or monthly period began (0 for all time). */
    public record Ranking(Period period, int totalRanked, int offset, long trackingStarted, long windowStart, List<Playtime> entries) {
    }

    /** One player's place in the all-time ranking, flat so the HTTP API keeps its original JSON shape. */
    public record Standing(int rank, int totalRanked, String discordId, UUID uuid, String name, boolean online,
                           long playtimeSeconds, long weekSeconds, long monthSeconds, long trackingStarted,
                           boolean original, Long firstJoin, Long lastSeen, Long onlineSince) {
    }

    /** A checked member's playtime and the quota they're held to. */
    public record ActivityEntry(Link link, long seconds, long required) {
    }

    /**
     * Members measured over the last {@code windowDays}: new members against {@code minSeconds}, Original
     * Supporters against {@code originalMinSeconds} (or skipped when it's 0). Members added to the sheet during the
     * window are listed separately since they haven't had a full week.
     */
    public record ActivityReport(long trackingStarted, long windowStart, int windowDays, long minSeconds, long originalMinSeconds,
                                 int originalsSkipped, int checked, List<ActivityEntry> below,
                                 List<ActivityEntry> recentlyAdded) {

        public boolean fullWindowTracked() {
            return trackingStarted <= windowStart;
        }
    }

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    public record KnownPlayer(UUID uuid, String name) {
    }

    public static final long DB_TIMEOUT_MS = 5_000;
    public static final long IMPORT_TIMEOUT_MS = 30_000;

    private record Online(String name, long playTicks) {
    }

    private final LinkRepository links;
    private final SessionRepository sessions;
    private final InventoryRepository inventories;
    private final StatsReader stats;
    private final SyncBridge sync;
    private final Logger logger;

    public PlayerService(LinkRepository links, SessionRepository sessions, InventoryRepository inventories,
                         StatsReader stats, SyncBridge sync, Logger logger) {
        this.links = links;
        this.sessions = sessions;
        this.inventories = inventories;
        this.stats = stats;
        this.sync = sync;
        this.logger = logger;
    }

    private java.time.DayOfWeek weekResetDay;
    private java.time.LocalTime weekResetTime;
    private java.time.ZoneId weekResetZone;

    /** Makes the weekly leaderboard reset every week at this time (with the weekly recap) instead of rolling. */
    public void setWeekReset(java.time.DayOfWeek day, java.time.LocalTime time, java.time.ZoneId zone) {
        this.weekResetDay = day;
        this.weekResetTime = time;
        this.weekResetZone = zone;
    }

    /**
     * When the period being ranked began: the last weekly reset, the 1st of this calendar month (so "this month" never
     * carries last month's playtime), or {@code days} ago for a rolling period when no time zone is set.
     */
    public long periodStart(Period period, long now) {
        if (period == Period.ALL_TIME) {
            return 0;
        }
        if (period == Period.WEEKLY && weekResetDay != null) {
            return lastReset(java.time.Instant.ofEpochMilli(now).atZone(weekResetZone), weekResetDay, weekResetTime).toInstant().toEpochMilli();
        }
        if (period == Period.MONTHLY && weekResetZone != null) {
            return monthStart(java.time.Instant.ofEpochMilli(now).atZone(weekResetZone)).toInstant().toEpochMilli();
        }
        return now - period.days * DAY_MS;
    }

    /** Midnight on the 1st of {@code now}'s month, in its zone. */
    static java.time.ZonedDateTime monthStart(java.time.ZonedDateTime now) {
        return now.toLocalDate().withDayOfMonth(1).atStartOfDay(now.getZone());
    }

    /** The latest {@code day} at {@code time} at or before {@code now}. */
    static java.time.ZonedDateTime lastReset(java.time.ZonedDateTime now, java.time.DayOfWeek day, java.time.LocalTime time) {
        java.time.ZonedDateTime candidate = now.with(java.time.temporal.TemporalAdjusters.previousOrSame(day)).with(time).withSecond(0).withNano(0);
        return candidate.isAfter(now) ? candidate.minusWeeks(1) : candidate;
    }

    public int onlineCount() throws Exception {
        return sync.call(() -> Bukkit.getOnlinePlayers().size());
    }

    public java.util.Optional<Link> linkByName(String name) throws Exception {
        return SyncBridge.await(links.findByName(name), DB_TIMEOUT_MS);
    }

    public Link link(String discordId) throws Exception {
        return SyncBridge.await(links.findByDiscord(discordId), DB_TIMEOUT_MS)
                .orElseThrow(() -> ApiException.notLinked(discordId));
    }

    public Stats stats(String discordId) throws Exception {
        Link link = link(discordId);
        record Presence(boolean online, String name, String world, long firstPlayed, long lastSeen, Counters live) {
        }
        Presence presence = sync.call(() -> {
            Player player = Bukkit.getPlayer(link.uuid());
            if (player != null) {
                return new Presence(true, player.getName(), player.getWorld().getName(),
                        player.getFirstPlayed(), System.currentTimeMillis(), stats.live(player));
            }
            OfflinePlayer offline = Bukkit.getOfflinePlayer(link.uuid());
            return new Presence(false, offline.getName(), null, offline.getFirstPlayed(), offline.getLastSeen(), null);
        });

        // The stats file is read here, off the main thread.
        Counters counters = presence.live() != null ? presence.live() : stats.fromFile(link.uuid());
        return new Stats(
                link.uuid(),
                presence.name() != null ? presence.name() : link.name(),
                presence.online(),
                presence.world(),
                round(counters.playTicks() / 72_000.0),
                counters.deaths(),
                counters.mobKills(),
                counters.playerKills(),
                counters.blocksMined(),
                round(counters.walkedCm() / 100_000.0),
                presence.firstPlayed() > 0 ? presence.firstPlayed() : null,
                presence.lastSeen() > 0 ? presence.lastSeen() : null);
    }

    public Inventory inventory(String discordId) throws Exception {
        Link link = link(discordId);
        UUID uuid = link.uuid();

        Inventory live = sync.call(() -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                return null;
            }
            return view(uuid, player.getName(), true, false, System.currentTimeMillis(), InventorySnapshot.of(player));
        });
        if (live != null) {
            return live;
        }

        CachedInventory cached = SyncBridge.await(inventories.load(uuid), DB_TIMEOUT_MS)
                .orElseThrow(() -> new ApiException(404, "no_inventory",
                        link.name() + " has no cached inventory yet. It is saved when they log out."));
        return sync.call(() -> {
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            InventorySnapshot snapshot = new InventorySnapshot(
                    InventoryCodec.decode(cached.inventory()),
                    InventoryCodec.decode(cached.armor()),
                    InventoryCodec.decode(cached.ender()));
            return view(uuid, name != null ? name : link.name(), false, true, cached.updatedAt(), snapshot);
        });
    }

    public Standing playtime(String discordId) throws Exception {
        List<Playtime> ranking = ranking(Period.ALL_TIME);
        for (Playtime entry : ranking) {
            if (entry.discordId().equals(discordId)) {
                long now = System.currentTimeMillis();
                // First join, last seen and (when online) the current login, as epoch ms or null.
                Long[] times = sync.call(() -> {
                    Player player = Bukkit.getPlayer(entry.uuid());
                    OfflinePlayer offline = player != null ? player : Bukkit.getOfflinePlayer(entry.uuid());
                    return new Long[] {
                            offline.getFirstPlayed() > 0 ? offline.getFirstPlayed() : null,
                            player != null ? Long.valueOf(now) : (offline.getLastSeen() > 0 ? offline.getLastSeen() : null),
                            player != null && player.getLastLogin() > 0 ? player.getLastLogin() : null};
                });
                long week = SyncBridge.await(sessions.playedSince(now - 7 * DAY_MS, now), DB_TIMEOUT_MS)
                        .getOrDefault(entry.uuid(), 0L) / 1000;
                long month = SyncBridge.await(sessions.playedSince(periodStart(Period.MONTHLY, now), now), DB_TIMEOUT_MS)
                        .getOrDefault(entry.uuid(), 0L) / 1000;
                return new Standing(entry.rank(), ranking.size(), entry.discordId(), entry.uuid(), entry.name(),
                        entry.online(), entry.playtimeSeconds(), week, month, trackingStarted(),
                        link(discordId).original(), times[0], times[1], times[2]);
            }
        }
        throw ApiException.notLinked(discordId);
    }

    public Ranking leaderboard(Period period, int limit, int offset) throws Exception {
        List<Playtime> ranking = ranking(period);
        int from = Math.min(offset, ranking.size());
        int to = Math.min(offset + limit, ranking.size());
        return new Ranking(period, ranking.size(), offset, trackingStarted(), periodStart(period, System.currentTimeMillis()),
                List.copyOf(ranking.subList(from, to)));
    }

    /** Every linked player ranked by all-time playtime. */
    public List<Playtime> allTimeRanking() throws Exception {
        return ranking(Period.ALL_TIME);
    }

    public List<Link> links() throws Exception {
        return SyncBridge.await(links.findAll(), DB_TIMEOUT_MS);
    }

    /** Seconds played per player since {@code since}, from recorded sessions. */
    public Map<UUID, Long> secondsPlayedSince(long since) throws Exception {
        long now = System.currentTimeMillis();
        Map<UUID, Long> seconds = new HashMap<>();
        SyncBridge.await(sessions.playedSince(since, now), DB_TIMEOUT_MS)
                .forEach((uuid, ms) -> seconds.put(uuid, ms / 1000));
        return seconds;
    }

    /** Seconds one player played between {@code since} and {@code until}, from recorded sessions. */
    public long secondsPlayedBetween(UUID uuid, long since, long until) throws Exception {
        return SyncBridge.await(sessions.playedSince(since, until), DB_TIMEOUT_MS).getOrDefault(uuid, 0L) / 1000;
    }

    /** A player who has joined the server, by name (any case), with their name as the server knows it. */
    public java.util.Optional<KnownPlayer> knownPlayer(String name) throws Exception {
        return java.util.Optional.ofNullable(knownPlayers(List.of(name)).get(name.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * When a player was online between {@code from} and {@code to}. Sessions are saved once a minute, so
     * for a player online now the last one is stretched to the present.
     */
    public List<SessionRepository.Interval> onlineIntervals(UUID uuid, long from, long to) throws Exception {
        List<SessionRepository.Interval> intervals = new ArrayList<>(
                SyncBridge.await(sessions.sessionsBetween(uuid, from, to), DB_TIMEOUT_MS));
        boolean online = sync.call(() -> Bukkit.getPlayer(uuid) != null);
        if (online && !intervals.isEmpty()) {
            SessionRepository.Interval last = intervals.remove(intervals.size() - 1);
            intervals.add(new SessionRepository.Interval(last.start(), System.currentTimeMillis()));
        }
        return intervals;
    }

    /** Current all-time vanilla counters for each player: live for anyone online, from the stats file otherwise. */
    public Map<UUID, Counters> counters(List<Link> players) throws Exception {
        Map<UUID, Counters> live = sync.call(() -> {
            Map<UUID, Counters> found = new HashMap<>();
            for (Link link : players) {
                Player player = Bukkit.getPlayer(link.uuid());
                if (player != null) {
                    found.put(link.uuid(), stats.live(player));
                }
            }
            return found;
        });
        Map<UUID, Counters> all = new HashMap<>(live);
        for (Link link : players) {
            if (!all.containsKey(link.uuid())) {
                try {
                    all.put(link.uuid(), stats.fromFile(link.uuid()));
                } catch (IOException | RuntimeException e) {
                    logger.log(Level.WARNING, "Could not read the stats file of " + link.uuid(), e);
                }
            }
        }
        return all;
    }

    public long trackingStarted() throws Exception {
        return SyncBridge.await(sessions.trackingStarted(), DB_TIMEOUT_MS);
    }

    /**
     * With {@code includeRecent}, members added to the sheet during the window are checked like everyone
     * else instead of being skipped (the admin forced the check).
     */
    /** {@code seasonStart}: playtime before it doesn't count, as if tracking had only started then. */
    public ActivityReport activityReport(int windowDays, long minSeconds, long originalMinSeconds, boolean includeRecent,
                                         long seasonStart) throws Exception {
        long now = System.currentTimeMillis();
        long windowStart = now - windowDays * DAY_MS;
        List<Link> all = SyncBridge.await(links.findAll(), DB_TIMEOUT_MS);
        Map<UUID, Long> played = SyncBridge.await(sessions.playedSince(windowStart, now), DB_TIMEOUT_MS);

        int originals = 0;
        int checked = 0;
        List<ActivityEntry> below = new ArrayList<>();
        List<ActivityEntry> recent = new ArrayList<>();
        for (Link link : all) {
            if (link.original() && originalMinSeconds <= 0) {
                originals++;
                continue;
            }
            long required = link.original() ? originalMinSeconds : minSeconds;
            ActivityEntry entry = new ActivityEntry(link, played.getOrDefault(link.uuid(), 0L) / 1000, required);
            if (!includeRecent && link.linkedAt() > windowStart) {
                recent.add(entry);
                continue;
            }
            checked++;
            if (entry.seconds() < required) {
                below.add(entry);
            }
        }
        below.sort(Comparator.comparingLong(ActivityEntry::seconds));
        return new ActivityReport(Math.max(trackingStarted(), seasonStart), windowStart, windowDays, minSeconds, originalMinSeconds,
                originals, checked, below, recent);
    }

    /** Names the server has seen join, keyed by lower-case name. */
    public Map<String, KnownPlayer> knownPlayers(List<String> names) throws Exception {
        return sync.call(() -> {
            Map<String, KnownPlayer> found = new LinkedHashMap<>();
            for (String name : names) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(name.trim());
                if (player != null) {
                    found.put(name.trim().toLowerCase(Locale.ROOT), new KnownPlayer(player.getUniqueId(),
                            player.getName() != null ? player.getName() : name.trim()));
                }
            }
            return found;
        });
    }

    /** Every linked player by playtime over the period; players with no playtime sit at 0. */
    private List<Playtime> ranking(Period period) throws Exception {
        List<Link> all = SyncBridge.await(links.findAll(), DB_TIMEOUT_MS);
        long now = System.currentTimeMillis();
        Map<UUID, Long> windowed = period == Period.ALL_TIME
                ? Map.of()
                : SyncBridge.await(sessions.playedSince(periodStart(period, now), now), DB_TIMEOUT_MS);
        Map<UUID, Online> online = sync.call(() -> {
            Map<UUID, Online> found = new HashMap<>();
            for (Link link : all) {
                Player player = Bukkit.getPlayer(link.uuid());
                if (player != null) {
                    found.put(link.uuid(), new Online(player.getName(), player.getStatistic(Statistic.PLAY_ONE_MINUTE)));
                }
            }
            return found;
        });

        record Row(Link link, String name, boolean online, long ticks) {
        }
        List<Row> rows = new ArrayList<>();
        for (Link link : all) {
            Online live = online.get(link.uuid());
            long ticks;
            if (period != Period.ALL_TIME) {
                // Session milliseconds converted to ticks so both kinds of period rank the same way.
                ticks = windowed.getOrDefault(link.uuid(), 0L) / 50;
            } else if (live != null) {
                ticks = live.playTicks();
            } else {
                try {
                    ticks = stats.fromFile(link.uuid()).playTicks();
                } catch (IOException | RuntimeException e) {
                    logger.log(Level.WARNING, "Could not read the stats file of " + link.uuid(), e);
                    ticks = 0;
                }
            }
            rows.add(new Row(link, live != null ? live.name() : link.name(), live != null, ticks));
        }
        rows.sort(Comparator.comparingLong(Row::ticks).reversed()
                .thenComparing(r -> r.name() == null ? "" : r.name(), String.CASE_INSENSITIVE_ORDER));

        List<Playtime> ranked = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            ranked.add(new Playtime(i + 1, row.link().discordId(), row.link().uuid(), row.name(), row.online(),
                    row.ticks() / 20));
        }
        return ranked;
    }

    private static Inventory view(UUID uuid, String name, boolean online, boolean stale, long updatedAt,
                                  InventorySnapshot snapshot) {
        return new Inventory(uuid, name, online, stale, updatedAt,
                ItemView.describe(snapshot.inventory()),
                ItemView.describe(snapshot.armor()),
                ItemView.describe(snapshot.ender()));
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
