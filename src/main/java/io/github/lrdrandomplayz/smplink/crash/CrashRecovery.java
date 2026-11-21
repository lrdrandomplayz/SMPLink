package io.github.lrdrandomplayz.smplink.crash;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import io.github.lrdrandomplayz.smplink.crash.CrashCase.MissingItem;
import io.github.lrdrandomplayz.smplink.crash.CrashCase.Pos;
import io.github.lrdrandomplayz.smplink.crash.CrashSnapshotRepository.PlayerRef;
import io.github.lrdrandomplayz.smplink.crash.CrashSnapshotRepository.Snap;
import io.github.lrdrandomplayz.smplink.crash.CrashSnapshotRepository.Snapshot;
import io.github.lrdrandomplayz.smplink.crash.ShutdownMarker.CrashInfo;
import io.github.lrdrandomplayz.smplink.inventory.InventoryCodec;
import io.github.lrdrandomplayz.smplink.inventory.InventorySnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * After a crash: works out, for each player who was online, what the rollback cost them, and hands staff a
 * {@link CrashCase}. The maths ({@link #inferSaveTime}, {@link #diff}) is pure; the rest touches Bukkit.
 * <p>
 * Flow: {@link #begin(CrashInfo)} files an "awaiting" case for everyone with a snapshot in the 90 s before the
 * crash. {@link CrashJoinListener} calls {@link #onJoin} when they come back; 2 s later their inventory is compared
 * with the newest pre-crash snapshot and the callback gets the case (off the main thread).
 */
public final class CrashRecovery {

    /** Players who were online at the crash have a snapshot at most this old. */
    static final long ONLINE_WINDOW_MS = 90_000;
    /** How far back the save-time guess looks (players lost up to 7 minutes). */
    static final long HISTORY_MS = 15 * 60_000L;
    static final double NEAR_BLOCKS = 64;
    static final double TIE_BLOCKS = 0.5;
    static final double MIN_MOVED = 8;
    private static final long JOIN_DELAY_TICKS = 40;
    private static final long KEEP_CASES_MS = 14L * 24 * 3600 * 1000;

    /** Where the join position best matches a snapshot: its time, and whether the match was weak. */
    public record SaveGuess(long time, boolean uncertain) {
    }

    /** A stack total. {@code key} identifies similar items; {@code data} is one item, Base64 (null in tests). */
    public record ItemCount(String key, String display, int amount, String data) {
    }

    /** What an action did: the message for staff and the updated case (to redraw the card). */
    public record Outcome(String message, CrashCase updated) {
    }

    private final Plugin plugin;
    private final CrashSnapshotRepository snapshots;
    private final CrashCaseRepository cases;
    private final Consumer<CrashCase> onCase;
    private final Logger logger;
    private final Map<Long, CrashCase> latest = new ConcurrentHashMap<>();

    /** @param onCase called, off the main thread, with each case staff should look at */
    public CrashRecovery(Plugin plugin, CrashSnapshotRepository snapshots, CrashCaseRepository cases,
                         Consumer<CrashCase> onCase) {
        this.plugin = plugin;
        this.snapshots = snapshots;
        this.cases = cases;
        this.onCase = onCase;
        this.logger = plugin.getLogger();
    }

    // ---------------------------------------------------------------- pure logic

    /**
     * The snapshot whose position is nearest the join position (same world; within 0.5 blocks of the nearest counts
     * as a tie, and the latest of the tied wins). With none within 64 blocks, the oldest snapshot's time, uncertain.
     */
    public static SaveGuess inferSaveTime(List<Snap> snaps, String world, double x, double y, double z) {
        if (snaps.isEmpty()) {
            return new SaveGuess(0, true);
        }
        double best = Double.MAX_VALUE;
        for (Snap s : snaps) {
            best = Math.min(best, distance(s, world, x, y, z));
        }
        if (best > NEAR_BLOCKS) {
            long oldest = Long.MAX_VALUE;
            for (Snap s : snaps) {
                oldest = Math.min(oldest, s.takenAt());
            }
            return new SaveGuess(oldest, true);
        }
        long time = Long.MIN_VALUE;
        for (Snap s : snaps) {
            if (distance(s, world, x, y, z) <= best + TIE_BLOCKS) {
                time = Math.max(time, s.takenAt());
            }
        }
        return new SaveGuess(time, false);
    }

    public static SaveGuess inferSaveTime(List<Snap> snaps, Location join) {
        return inferSaveTime(snaps, join.getWorld() == null ? null : join.getWorld().getName(),
                join.getX(), join.getY(), join.getZ());
    }

    private static double distance(Snap s, String world, double x, double y, double z) {
        if (s.world() == null || !s.world().equals(world)) {
            return Double.MAX_VALUE;
        }
        double dx = s.x() - x;
        double dy = s.y() - y;
        double dz = s.z() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** What is in {@code before} but not (or not as much) in {@code now}, matched by key; positives only, biggest first. */
    public static List<ItemCount> diff(Collection<ItemCount> before, Collection<ItemCount> now) {
        Map<String, Integer> have = new LinkedHashMap<>();
        for (ItemCount c : now) {
            have.merge(c.key(), c.amount(), Integer::sum);
        }
        Map<String, ItemCount> wanted = new LinkedHashMap<>();
        for (ItemCount c : before) {
            wanted.merge(c.key(), c, (a, b) -> new ItemCount(a.key(), a.display(), a.amount() + b.amount(), a.data()));
        }
        List<ItemCount> missing = new ArrayList<>();
        for (ItemCount c : wanted.values()) {
            int lost = c.amount() - have.getOrDefault(c.key(), 0);
            if (lost > 0) {
                missing.add(new ItemCount(c.key(), c.display(), lost, c.data()));
            }
        }
        missing.sort(Comparator.comparingInt(ItemCount::amount).reversed().thenComparing(ItemCount::display));
        return missing;
    }

    /** Totals per similar item across the arrays (empty slots skipped). Needs a running server for item data. */
    public static List<ItemCount> counts(ItemStack[]... arrays) {
        Map<String, ItemCount> totals = new LinkedHashMap<>();
        for (ItemStack[] array : arrays) {
            for (ItemStack item : array) {
                if (InventoryCodec.isEmpty(item)) {
                    continue;
                }
                ItemStack one = item.clone();
                one.setAmount(1);
                String data = Base64.getEncoder().encodeToString(one.serializeAsBytes());
                totals.merge(data, new ItemCount(data, pretty(item), item.getAmount(), data),
                        (a, b) -> new ItemCount(a.key(), a.display(), a.amount() + b.amount(), a.data()));
            }
        }
        return new ArrayList<>(totals.values());
    }

    static String pretty(ItemStack item) {
        StringBuilder out = new StringBuilder();
        for (String word : item.getType().name().toLowerCase(Locale.ROOT).split("_")) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- after a crash

    /** Files an awaiting case for everyone who was online at the crash; the future holds how many. Call once at startup. */
    public CompletableFuture<Integer> begin(CrashInfo info) {
        long crashedAt = info.crashedAt();
        cases.deleteOlderThan(System.currentTimeMillis() - KEEP_CASES_MS);
        return snapshots.playersBetween(crashedAt - ONLINE_WINDOW_MS, crashedAt).thenCompose(players -> {
            List<CompletableFuture<Long>> filed = new ArrayList<>();
            for (PlayerRef p : players) {
                filed.add(cases.addAwaiting(p.uuid(), p.name(), crashedAt));
            }
            return CompletableFuture.allOf(filed.toArray(new CompletableFuture[0])).thenApply(v -> players.size());
        });
    }

    /** Called when a player joins (see {@link CrashJoinListener}); does the work 2 seconds later. */
    public void onJoin(Player player) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                handleJoin(player);
            }
        }, JOIN_DELAY_TICKS);
    }

    private void handleJoin(Player player) {
        UUID uuid = player.getUniqueId();
        Pos join = pos(player.getLocation());
        InventorySnapshot inv = InventorySnapshot.of(player);
        List<ItemCount> now = counts(CrashSnapshotter.combined(inv), inv.ender());

        cases.givePending(uuid).thenAccept(list -> {
            for (CrashCase c : list) {
                runMain(() -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) {
                        give(c);
                    }
                });
            }
        }).exceptionally(e -> failed("pending items for " + player.getName(), e));

        cases.awaiting(uuid).thenCompose(awaiting -> {
            if (awaiting.isEmpty()) {
                return CompletableFuture.<Void>completedFuture(null);
            }
            CrashCase waiting = awaiting.get();
            long crash = waiting.crashTime();
            return snapshots.positions(uuid, crash - HISTORY_MS, crash).thenCombine(
                    snapshots.latestBefore(uuid, crash), (positions, newest) -> new Object[] {positions, newest})
                    .thenAccept(pair -> {
                        @SuppressWarnings("unchecked")
                        List<Snap> positions = (List<Snap>) pair[0];
                        @SuppressWarnings("unchecked")
                        Optional<Snapshot> newest = (Optional<Snapshot>) pair[1];
                        runMain(() -> evaluate(waiting, positions, newest, now, join, player.getName()));
                    });
        }).exceptionally(e -> failed("rollback check for " + player.getName(), e));
    }

    /** Main thread: decodes the snapshot, then does the slow CoreProtect lookup off-thread and files the case. */
    private void evaluate(CrashCase waiting, List<Snap> positions, Optional<Snapshot> newest, List<ItemCount> now,
                          Pos join, String name) {
        if (newest.isEmpty()) {
            cases.save(waiting.with("skipped", false, false, false, "No snapshot to compare with."));
            return;
        }
        Snapshot snap = newest.get();
        List<ItemCount> before;
        try {
            before = counts(InventoryCodec.decode(snap.inventory()), InventoryCodec.decode(snap.ender()));
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "[crash] couldn't read " + name + "'s snapshot", e);
            cases.save(waiting.with("skipped", false, false, false, "Snapshot unreadable: " + e.getMessage()));
            return;
        }
        List<MissingItem> missing = new ArrayList<>();
        for (ItemCount c : diff(before, now)) {
            missing.add(new MissingItem(c.display(), c.amount(), c.data()));
        }
        Pos real = new Pos(snap.world(), snap.x(), snap.y(), snap.z());
        double moved = real.distance(join);
        if (missing.isEmpty() && moved < MIN_MOVED) {
            cases.save(waiting.with("skipped", false, false, false, "Nothing missing and barely moved."));
            return;
        }
        SaveGuess guess = inferSaveTime(positions, join.world(), join.x(), join.y(), join.z());
        long saveTime = guess.time() == 0 ? snap.takenAt() : guess.time();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> dupes = CrashCoreProtect.withdrawals(name, saveTime, waiting.crashTime(), logger);
            CrashCase filed = new CrashCase(waiting.id(), waiting.uuid(), name, saveTime, guess.uncertain(),
                    waiting.crashTime(), missing, real, join, moved, dupes, "open", false, false, false, "");
            latest.put(filed.id(), filed);
            cases.save(filed).thenRun(() -> onCase.accept(filed)).exceptionally(e -> failed("saving case " + filed.id(), e));
        });
    }

    // ---------------------------------------------------------------- staff actions

    /** The newest version of a case (blocks briefly on the database: call it off the main thread). */
    public Optional<CrashCase> load(long id) {
        CrashCase cached = latest.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            return cases.get(id).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warning("[crash] couldn't load case " + id + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private void store(CrashCase updated) {
        latest.put(updated.id(), updated);
        cases.save(updated).exceptionally(e -> failed("saving case " + updated.id(), e));
    }

    /** Main thread. Gives the missing items, or queues them for the next join if the player is offline. */
    public Outcome give(CrashCase c) {
        if (c.gave()) {
            return new Outcome("Items were already given.", c);
        }
        Player player = Bukkit.getPlayer(c.uuid());
        if (player == null) {
            if (c.givePending()) {
                return new Outcome(c.name() + " is offline; the items are already queued.", c);
            }
            CrashCase queued = c.with(c.status(), false, c.tped(), true, c.log())
                    .withLog(c.name() + " is offline; items queued for their next join.");
            store(queued);
            return new Outcome(c.name() + " is offline; the items will be given when they next join.", queued);
        }
        int dropped = giveMissing(player, c);
        String line = "Gave " + total(c) + " item" + (total(c) == 1 ? "" : "s") + " to " + c.name()
                + (dropped > 0 ? " (" + dropped + " dropped at their feet, inventory full)" : "") + ".";
        CrashCase done = c.with(c.status(), true, c.tped(), false, c.log()).withLog(line);
        store(done);
        return new Outcome(line, done);
    }

    /** Main thread. Adds the missing items to the player's inventory and drops what doesn't fit; returns the dropped count. */
    public int giveMissing(Player player, CrashCase c) {
        int dropped = 0;
        for (MissingItem m : c.missing()) {
            ItemStack item;
            try {
                item = ItemStack.deserializeBytes(Base64.getDecoder().decode(m.data()));
            } catch (RuntimeException e) {
                logger.warning("[crash] couldn't rebuild " + m.name() + " for " + c.name() + ": " + e.getMessage());
                continue;
            }
            int left = m.amount();
            int stack = Math.max(1, item.getMaxStackSize());
            while (left > 0) {
                ItemStack part = item.clone();
                part.setAmount(Math.min(stack, left));
                left -= part.getAmount();
                for (ItemStack over : player.getInventory().addItem(part).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), over);
                    dropped += over.getAmount();
                }
            }
        }
        player.sendMessage(net.kyori.adventure.text.Component.text(
                "Staff restored the items you lost in the crash. Sorry about that!",
                net.kyori.adventure.text.format.NamedTextColor.GREEN));
        return dropped;
    }

    /** Main thread. Teleports the player to where they really were when the server died. */
    public Outcome teleportBack(Player player, CrashCase c) {
        World world = c.real() == null ? null : Bukkit.getWorld(c.real().world());
        if (world == null) {
            return new Outcome("Can't teleport: world " + (c.real() == null ? "?" : c.real().world()) + " isn't loaded.", c);
        }
        player.teleport(new Location(world, c.real().x(), c.real().y(), c.real().z(),
                player.getLocation().getYaw(), player.getLocation().getPitch()));
        CrashCase done = c.with(c.status(), c.gave(), true, c.givePending(), c.log()).withLog(c.name() + " was teleported back.");
        store(done);
        return new Outcome(c.name() + " was teleported back.", done);
    }

    /** Main thread. Teleports by UUID, or says the player is offline. */
    public Outcome teleportBack(CrashCase c) {
        Player player = Bukkit.getPlayer(c.uuid());
        return player == null ? new Outcome(c.name() + " is offline; nothing was done.", c) : teleportBack(player, c);
    }

    /** Closes the case without doing anything. */
    public Outcome skip(CrashCase c) {
        CrashCase done = c.with("skipped", c.gave(), c.tped(), false, c.log()).withLog("Skipped.");
        store(done);
        return new Outcome("Skipped.", done);
    }

    private static int total(CrashCase c) {
        int sum = 0;
        for (MissingItem m : c.missing()) {
            sum += m.amount();
        }
        return sum;
    }

    // ---------------------------------------------------------------- helpers

    private static Pos pos(Location l) {
        return new Pos(l.getWorld() == null ? null : l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
    }

    private void runMain(Runnable task) {
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (IllegalStateException | org.bukkit.plugin.IllegalPluginAccessException e) {
            logger.fine("[crash] plugin disabled; dropped a task");
        }
    }

    private Void failed(String what, Throwable error) {
        logger.log(Level.WARNING, "[crash] " + what + " failed", error);
        return null;
    }
}
