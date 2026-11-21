package io.github.lrdrandomplayz.smplink.crash;

import java.util.List;
import java.util.UUID;

/**
 * One player's rollback after a crash. status: awaiting (was online at the crash, hasn't rejoined), open (staff
 * decide), done, skipped. {@code log} is the outcome lines staff actions added, newline separated.
 */
public record CrashCase(long id, UUID uuid, String name, long saveTime, boolean saveUncertain, long crashTime,
                        List<MissingItem> missing, Pos real, Pos join, double moved, List<String> dupeWarnings,
                        String status, boolean gave, boolean tped, boolean givePending, String log) {

    /** An item lost in the rollback. {@code data} is the item (amount 1) from serializeAsBytes, Base64. */
    public record MissingItem(String name, int amount, String data) {
    }

    public record Pos(String world, double x, double y, double z) {

        /** Blocks between two points; huge when they're in different worlds. */
        public double distance(Pos other) {
            if (other == null || world == null || !world.equals(other.world)) {
                return 1e9;
            }
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    public boolean open() {
        return status.equals("open");
    }

    public long rolledBackMillis() {
        return Math.max(0, crashTime - saveTime);
    }

    public CrashCase with(String status, boolean gave, boolean tped, boolean givePending, String log) {
        return new CrashCase(id, uuid, name, saveTime, saveUncertain, crashTime, missing, real, join, moved,
                dupeWarnings, status, gave, tped, givePending, log);
    }

    public CrashCase withLog(String line) {
        return with(status, gave, tped, givePending, log.isEmpty() ? line : log + "\n" + line);
    }
}
