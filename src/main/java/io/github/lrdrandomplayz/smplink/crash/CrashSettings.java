package io.github.lrdrandomplayz.smplink.crash;

import org.bukkit.configuration.ConfigurationSection;

/** crash-recovery.* from config.yml. Empty or "off" channel IDs turn that part off. */
public record CrashSettings(boolean enabled, int snapshotSeconds, int keep, String staffChannelId,
                            String announceChannelId, boolean healthEnabled, double memoryAlert, double tpsAlert,
                            boolean relief) {

    public CrashSettings {
        snapshotSeconds = Math.max(10, snapshotSeconds);
        keep = Math.max(1, keep);
        staffChannelId = id(staffChannelId);
        announceChannelId = id(announceChannelId);
        memoryAlert = Double.isNaN(memoryAlert) ? 0.90 : Math.max(0.10, Math.min(1.0, memoryAlert));
        tpsAlert = Double.isNaN(tpsAlert) ? 15 : Math.max(1, Math.min(20, tpsAlert));
    }

    private static String id(String value) {
        return value == null || value.isBlank() || value.trim().equalsIgnoreCase("off") ? "" : value.trim();
    }

    public static CrashSettings defaults() {
        return new CrashSettings(true, 30, 20, "", "", true, 0.90, 15, true);
    }

    /** Reads the {@code crash-recovery} section of the plugin's config (pass the root); missing keys use defaults. */
    public static CrashSettings from(ConfigurationSection config) {
        ConfigurationSection s = config == null ? null : config.getConfigurationSection("crash-recovery");
        if (s == null) {
            return defaults();
        }
        ConfigurationSection h = s.getConfigurationSection("health");
        return new CrashSettings(
                s.getBoolean("enabled", true),
                s.getInt("snapshot-seconds", 30),
                s.getInt("keep", 20),
                s.getString("staff-channel-id", ""),
                s.getString("announce-channel-id", ""),
                h == null || h.getBoolean("enabled", true),
                h == null ? 0.90 : h.getDouble("memory-alert", 0.90),
                h == null ? 15 : h.getDouble("tps-alert", 15),
                h == null || h.getBoolean("relief", true));
    }

    public boolean staffChannelSet() {
        return !staffChannelId.isEmpty();
    }
}
