package io.github.lrdrandomplayz.smplink;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Locale;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public record PluginSettings(
        boolean discordEnabled,
        String discordToken,
        String guildId,
        String adminRoleId,
        String statusText,
        ZoneId statusZone,
        double activityMinHours,
        String activityLogChannelId,
        String activityDmMessage,
        boolean sheetEnabled,
        String sheetId,
        String sheetTab,
        Path sheetKeyFile,
        int syncIntervalMinutes,
        boolean allowRelinking,
        int inventoryCacheIntervalSeconds,
        boolean activityWeekly,
        DayOfWeek activityDay,
        LocalTime activityTime,
        String recapChannelId,
        DayOfWeek recapDay,
        LocalTime recapTime,
        String milestoneChannelId,
        List<Integer> milestoneHours,
        String milestoneMessage,
        boolean whitelistEnabled,
        String whitelistAddCommand,
        String whitelistRemoveCommand,
        String linkedRoleId,
        boolean nicknameSync,
        String nicknameFormat,
        boolean removeMissingLinks,
        boolean backupEnabled,
        int backupKeep,
        int activityGraceDays,
        String activityPassedMessage,
        String activityRemovedMessage,
        String whitelistRoleId,
        String activityRemovalChannelId,
        Map<Integer, String> milestoneRoles,
        boolean milestoneRolesHighestOnly,
        String welcomeMessage,
        String auditChannelId,
        String welcomeChannelId,
        String firstJoinChannelId,
        boolean ingameReminders,
        int ingameReminderMinutes,
        String tradeChannelId,
        double tradeExpireDays,
        int tradePostsPerHour,
        String tradeFooter,
        int strikeLimit,
        int strikeExpireDays,
        String activityStrikeMessage,
        int strikeExtendedLimit,
        List<String> strikeExtendedRoles,
        String strikeMessage,
        String strikeRemovedMessage,
        List<String> activityExemptRoles,
        String tradeMiddlemanRoleId,
        String whitelistDonorRoleId,
        double activityOriginalMinHours
) {


    /** Reads a value, falling back to the jar's config.yml; Bukkit skips that fallback if a default is passed in. */
    private static String text(FileConfiguration config, String path) {
        String value = config.getString(path);
        return value == null ? "" : value;
    }

    private static DayOfWeek day(FileConfiguration config, String path) {
        try {
            return DayOfWeek.valueOf(text(config, path).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path + " must be a weekday such as SUNDAY, got \"" + text(config, path) + "\"");
        }
    }

    private static LocalTime time(FileConfiguration config, String path) {
        try {
            return LocalTime.parse(text(config, path).trim());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(path + " must be a 24-hour time such as \"20:00\", got \"" + text(config, path) + "\"");
        }
    }


    /** A channel or role ID; empty or "off" turns the feature off. */
    private static String channel(FileConfiguration config, String path) {
        String value = text(config, path).trim();
        return value.equalsIgnoreCase("off") ? "" : value;
    }


    /** A list of Discord role IDs, written with or without quotes. */
    private static List<String> roleIds(FileConfiguration config, String path) {
        return config.getList(path, List.of()).stream()
                .map(value -> String.valueOf(value).trim())
                .filter(value -> !value.isEmpty() && !value.equals("null"))
                .toList();
    }

    /** milestones.roles as hours to role ID, in ascending order of hours. */
    private static Map<Integer, String> milestoneRoles(FileConfiguration config, Logger logger) {
        Map<Integer, String> roles = new TreeMap<>();
        ConfigurationSection section = config.getConfigurationSection("milestones.roles");
        if (section == null) {
            return roles;
        }
        for (String key : section.getKeys(false)) {
            String roleId = String.valueOf(section.get(key)).trim();
            try {
                int hours = Integer.parseInt(key.trim());
                if (!roleId.isEmpty() && !roleId.equals("null")) {
                    roles.put(hours, roleId);
                }
            } catch (NumberFormatException e) {
                logger.warning("milestones.roles: \"" + key + "\" is not a number of hours; ignored.");
            }
        }
        return Collections.unmodifiableMap(roles);
    }

    public static PluginSettings load(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        Logger logger = plugin.getLogger();
        File file = new File(plugin.getDataFolder(), "config.yml");
        String yamlError = null;
        try {
            new YamlConfiguration().load(file);
        } catch (IOException | InvalidConfigurationException e) {
            yamlError = e.getMessage();
        }
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        if (yamlError != null) {
            // Bukkit falls back to the defaults when the file can't be parsed; saving now would
            // overwrite the admin's edits (their token included), so the file is left alone.
            logger.severe("config.yml could not be read, so the plugin is using default settings and has NOT "
                    + "changed the file. Fix this and restart: " + yamlError.lines().limit(4).reduce((a, b) -> a + " | " + b).orElse(""));
        } else {
            // Adds sections introduced by newer versions to an existing config.yml.
            config.options().copyDefaults(true);
            // 1.13-1.16 kept the strike limit and expiry under activity-check; strikes now have their own section.
            for (String[] moved : new String[][]{{"activity-check.strikes-to-remove", "strikes.limit"},
                    {"activity-check.strike-expire-days", "strikes.expire-days"}}) {
                if (config.isSet(moved[0])) {
                    config.set(moved[1], config.getInt(moved[0]));
                    config.set(moved[0], null);
                }
            }
            plugin.saveConfig();
        }

        int interval = config.getInt("inventory-cache.interval-seconds");
        if (interval < 30) {
            throw new IllegalArgumentException("inventory-cache.interval-seconds must be at least 30, got " + interval);
        }
        int syncMinutes = config.getInt("sheet.sync-interval-minutes");
        if (syncMinutes < 1) {
            throw new IllegalArgumentException("sheet.sync-interval-minutes must be at least 1, got " + syncMinutes);
        }

        String token = text(config, "discord.token").trim();
        String guildId = text(config, "discord.guild-id").trim();
        boolean discordEnabled = config.getBoolean("discord.enabled");
        if (discordEnabled && (token.isEmpty() || guildId.isEmpty())) {
            logger.warning("Discord bot is off: " + (token.isEmpty() ? "discord.token" : "discord.guild-id")
                    + " is empty in " + file.getPath() + ".");
            discordEnabled = false;
        }

        ZoneId statusZone;
        try {
            statusZone = ZoneId.of(text(config, "discord.status-timezone").trim());
        } catch (DateTimeException e) {
            logger.warning("discord.status-timezone is not a valid time zone; using GMT+8.");
            statusZone = ZoneId.of("GMT+8");
        }

        double minHours = config.getDouble("activity-check.min-hours");
        if (minHours <= 0) {
            throw new IllegalArgumentException("activity-check.min-hours must be more than 0, got " + minHours);
        }

        String sheetId = text(config, "sheet.id").trim();
        boolean sheetEnabled = config.getBoolean("sheet.enabled");
        if (sheetEnabled && sheetId.isEmpty()) {
            sheetEnabled = false;
        }

        return new PluginSettings(
                discordEnabled,
                token,
                guildId,
                text(config, "discord.admin-role-id").trim(),
                text(config, "discord.status").trim(),
                statusZone,
                minHours,
                text(config, "activity-check.log-channel-id").trim(),
                text(config, "activity-check.warning-message"),
                sheetEnabled,
                sheetId,
                text(config, "sheet.tab").trim(),
                plugin.getDataFolder().toPath().resolve(text(config, "sheet.key-file").trim()),
                syncMinutes,
                config.getBoolean("link.allow-relinking"),
                interval,
                config.getBoolean("activity-check.weekly"),
                day(config, "activity-check.day"),
                time(config, "activity-check.time"),
                text(config, "recap.channel-id").trim(),
                day(config, "recap.day"),
                time(config, "recap.time"),
                text(config, "milestones.channel-id").trim(),
                config.getIntegerList("milestones.hours").stream().filter(h -> h > 0).sorted().distinct().toList(),
                text(config, "milestones.message"),
                config.getBoolean("whitelist.enabled"),
                text(config, "whitelist.add-command").trim(),
                text(config, "whitelist.remove-command").trim(),
                channel(config, "discord.linked-role-id"),
                config.getBoolean("discord.nickname-sync"),
                text(config, "discord.nickname-format"),
                config.getBoolean("link.remove-missing"),
                config.getBoolean("backup.enabled"),
                Math.max(1, config.getInt("backup.keep")),
                Math.max(1, config.getInt("activity-check.grace-days")),
                text(config, "activity-check.passed-message"),
                text(config, "activity-check.removed-message"),
                text(config, "discord.whitelist-role-id").trim(),
                text(config, "activity-check.removal-channel-id").trim(),
                milestoneRoles(config, logger),
                // Replaces 1.7.0's highest-only, whose saved "false" would otherwise override the new default.
                !config.getBoolean("milestones.keep-lower-roles"),
                text(config, "whitelist.welcome-message"),
                text(config, "notices.audit-channel-id").trim(),
                channel(config, "welcome.channel-id"),
                channel(config, "welcome.first-join-channel-id"),
                config.getBoolean("activity-check.ingame-reminders"),
                Math.max(0, config.getInt("activity-check.ingame-reminder-minutes")),
                channel(config, "trade.channel-id"),
                config.getDouble("trade.expire-days") > 0 ? config.getDouble("trade.expire-days") : 7,
                Math.max(1, config.getInt("trade.posts-per-hour")),
                text(config, "trade.footer").trim(),
                Math.max(1, config.getInt("strikes.limit")),
                Math.max(1, config.getInt("strikes.expire-days")),
                text(config, "activity-check.strike-message"),
                Math.max(1, config.getInt("strikes.extended-limit")),
                roleIds(config, "strikes.extended-roles"),
                text(config, "strikes.message"),
                text(config, "strikes.removed-message"),
                roleIds(config, "activity-check.exempt-roles"),
                channel(config, "trade.middleman-role-id"),
                channel(config, "whitelist.donor-role-id"),
                Math.max(0, config.getDouble("activity-check.original-min-hours")));
    }

}
