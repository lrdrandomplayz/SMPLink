package io.github.lrdrandomplayz.smplink.discord;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import io.github.lrdrandomplayz.smplink.db.TradeRepository;
import io.github.lrdrandomplayz.smplink.inventory.ItemView;
import io.github.lrdrandomplayz.smplink.service.PlayerService.ActivityReport;
import io.github.lrdrandomplayz.smplink.service.PlayerService.Inventory;
import io.github.lrdrandomplayz.smplink.service.PlayerService.Period;
import io.github.lrdrandomplayz.smplink.service.PlayerService.Playtime;
import io.github.lrdrandomplayz.smplink.service.PlayerService.Ranking;
import io.github.lrdrandomplayz.smplink.service.PlayerService.Standing;
import io.github.lrdrandomplayz.smplink.sync.SheetImporter.Report;
import io.github.lrdrandomplayz.smplink.sync.SheetParser.Problem;

/**
 * Every embed the bot sends. They share one look: violet for normal posts,
 * green / amber / red for success / warning / removal, and the server name (server.name) as the author line, with the
 * bot's avatar. Discord only renders {@code <t:…>} timestamps in descriptions and field values, so
 * dates never go in titles, field names or footers.
 */
final class Embeds {

    static final int BRAND = 0x8B5CF6;
    static final int SUCCESS = 0x3BA55D;
    static final int WARNING = 0xF0B232;
    static final int DANGER = 0xED4245;
    static final int MUTED = 0x5C5F66;

    private static final int FIELD_LIMIT = MessageEmbed.VALUE_MAX_LENGTH;
    // Discord allows 6000 characters per embed; leave room for the title, description and footer.
    private static final int LIST_BUDGET = 5000;
    // Glyphs, not their text: the text changes once the icons are uploaded, after this class has loaded.
    private static final Glyph[] MEDALS = {Glyph.FIRST, Glyph.SECOND, Glyph.THIRD};

    // Set once the bot has logged in; the author line shows it next to the server name.
    private static volatile String brandIcon;

    private Embeds() {
    }

    static void setBrandIcon(String url) {
        brandIcon = url;
    }

    /** The shared starting point for every embed. */
    static EmbedBuilder base(int color) {
        return new EmbedBuilder().setColor(color).setAuthor(io.github.lrdrandomplayz.smplink.Branding.name(), null, brandIcon);
    }

    // --- player commands ------------------------------------------------------------------------------

    /**
     * /playtime: the weekly activity quota. {@code exemptAs} names why the member isn't checked
     * ("Original member", "Donor"), in which case {@code quota} is ignored.
     */
    static MessageEmbed quota(String username, Standing standing, String quota, String required, String exemptAs) {
        EmbedBuilder embed = base(exemptAs != null ? SUCCESS : BRAND)
                .setTitle(Glyph.XP + " " + orUnknown(standing.name()) + "'s activity quota")
                .setThumbnail(skin("avatar", standing.name()));
        if (exemptAs != null) {
            return embed.setDescription(Glyph.SHIELD + " **" + exemptAs + "**: not part of the weekly activity check.\nLinked to **"
                    + username + "**").build();
        }
        return embed.setDescription(quota + "\n\nLinked to **" + username + "**")
                .setFooter("New members need " + required + " every 7 days. See /profile for more.")
                .build();
    }

    static MessageEmbed leaderboard(Ranking board, int page, int pageCount) {
        return leaderboard(board, page, pageCount, id -> "");
    }

    /** {@code badge} gives the mark after each name (empty for none), or "". */
    static MessageEmbed leaderboard(Ranking board, int page, int pageCount, java.util.function.Function<String, String> badge) {
        Period period = board.period();
        EmbedBuilder embed = base(BRAND)
                .setTitle(Glyph.TROPHY + " Playtime leaderboard")
                .setFooter("Page " + (page + 1) + " of " + pageCount + " · " + board.totalRanked()
                        + " linked players · 🟢 online now");
        StringBuilder lines = new StringBuilder();
        if (period == Period.ALL_TIME) {
            lines.append("**All time**");
        } else {
            long start = Math.max(board.windowStart(), board.trackingStarted());
            lines.append("**").append(period.label).append("** · ").append(timestamp(start, 'f')).append(" → now");
            if (start == board.trackingStarted()) {
                lines.append("\n*Playtime has been recorded since then.*");
            }
        }
        if (board.entries().isEmpty()) {
            return embed.setDescription(lines + "\n\nNobody is linked yet.").build();
        }
        lines.append('\n');
        for (Playtime entry : board.entries()) {
            // Mentions inside embeds render as names but never ping.
            lines.append('\n')
                    .append(entry.rank() <= 3 ? MEDALS[entry.rank() - 1] : "`#" + entry.rank() + "`")
                    .append(" **").append(orUnknown(entry.name())).append("**")
                    .append(badge.apply(entry.discordId()))
                    .append(entry.online() ? " " + Glyph.ONLINE : "")
                    .append(" · ").append(formatPlaytime(entry.playtimeSeconds()))
                    .append(" · <@").append(entry.discordId()).append('>');
        }
        return embed.setDescription(lines).build();
    }

    /** The picture carries the layout; the text only lists what icons can't show. */
    static MessageEmbed inventory(String username, Inventory inv, String imageName) {
        EmbedBuilder embed = base(inv.stale() ? MUTED : BRAND)
                .setTitle(Glyph.INVENTORY + " " + orUnknown(inv.name()) + "'s inventory")
                .setDescription(inv.stale()
                        ? Glyph.OFFLINE + " **Offline** · cached copy from " + timestamp(inv.updatedAt(), 'f') + " ("
                        + timestamp(inv.updatedAt(), 'R') + ")"
                        : Glyph.ONLINE + " **Online** · live inventory for **" + username + "**")
                .setFooter(inv.stale() ? "Cached when they last logged out" : "Live");

        if (imageName != null) {
            embed.setImage("attachment://" + imageName);
            List<String> special = Stream.of(inv.armor(), inv.inventory(), inv.enderChest())
                    .flatMap(List::stream)
                    .filter(item -> item.displayName() != null || !item.enchants().isEmpty())
                    .map(item -> Glyph.SPARKLE + " " + describe(item))
                    .toList();
            addList(embed, "Enchanted & named items", special);
            return embed.build();
        }

        embed.setThumbnail(skin("body", inv.name()));
        String[] armorNames = {"Boots", "Leggings", "Chestplate", "Helmet"};
        List<String> armor = new ArrayList<>();
        inv.armor().forEach(i -> armor.add(0, slotLine(i.slot() < 4 ? armorNames[i.slot()] : String.valueOf(i.slot()), i)));
        inv.inventory().stream().filter(i -> i.slot() == 40).forEach(i -> armor.add(slotLine("off", i)));
        addList(embed, Glyph.SHIELD + " Armor", armor);
        addList(embed, Glyph.STATS + " Hotbar", inv.inventory().stream().filter(i -> i.slot() <= 8).map(i -> slotLine(String.valueOf(i.slot()), i)).toList());
        addList(embed, Glyph.INVENTORY + " Inventory", inv.inventory().stream().filter(i -> i.slot() >= 9 && i.slot() <= 35).map(i -> slotLine(String.valueOf(i.slot()), i)).toList());
        addList(embed, Glyph.BRAND + " Ender chest", inv.enderChest().stream().map(i -> slotLine(String.valueOf(i.slot()), i)).toList());
        if (embed.getFields().isEmpty()) {
            embed.addField("Empty", "No items in inventory, armor or ender chest.", false);
        }
        return embed.build();
    }

    // --- staff reports ----------------------------------------------------------------------------------

    static MessageEmbed syncReport(Report report) {
        var result = report.result();
        boolean problems = report.rejected() > 0 || !result.conflicts().isEmpty()
                || report.removal().skippedReason() != null
                || (report.whitelist() != null && report.whitelist().error() != null);
        EmbedBuilder embed = base(problems ? WARNING : SUCCESS)
                .setTitle(Glyph.RESTART + " Sheet sync")
                .setDescription("Tab **" + report.tab() + "** · " + String.format(Locale.ROOT, "%.1f", report.durationMs() / 1000.0)
                        + " s\nUUIDs from the server " + report.sources().server() + " · Mojang " + report.sources().mojang()
                        + " · offline " + report.sources().offline() + " · " + report.byUsername()
                        + " matched by Discord username")
                .addField(Glyph.PLUS + " Linked", "**" + result.inserted() + "**", true)
                .addField(Glyph.EDIT + " Updated", "**" + result.updated() + "**", true)
                .addField(Glyph.MINUS + " Unlinked", "**" + report.removal().removed().size() + "**", true)
                .addField(Glyph.NOTE + " Whitelist", report.whitelist() == null ? "off"
                        : "**+" + report.whitelist().added().size() + "** / **−" + report.whitelist().removed().size() + "**", true)
                .addField(Glyph.YES + " Unchanged", "**" + result.unchanged() + "**", true)
                .addField(Glyph.WARN + " Rejected", "**" + (report.rejected() + result.conflicts().size()) + "**", true)
                .setFooter(report.withoutDiscord() + " rows have no Discord ID");
        if (report.removal().skippedReason() != null) {
            embed.addField(Glyph.WARN + " Unlinking skipped", report.removal().skippedReason(), false);
        }
        if (report.whitelist() != null && report.whitelist().error() != null) {
            embed.addField(Glyph.WARN + " Whitelist problem", report.whitelist().error(), false);
        }
        if (report.whitelist() != null && report.whitelist().skippedRemovals() != null) {
            embed.addField(Glyph.WARN + " Whitelist removals skipped", report.whitelist().skippedRemovals(), false);
        }
        addList(embed, Glyph.MINUS + " Unlinked", report.removal().removed().stream()
                .map(link -> "<@" + link.discordId() + "> · `" + link.name() + "`").toList());
        addList(embed, Glyph.NOTE + " Added to the whitelist", report.whitelist() == null ? List.of() : report.whitelist().added());
        addList(embed, Glyph.NOTE + " Removed from the whitelist", report.whitelist() == null ? List.of() : report.whitelist().removed());
        addList(embed, Glyph.QUESTION + " Unresolvable names", rows(report.invalidNames()));
        addList(embed, Glyph.TRADE + " Lookup failed (retried next sync)", rows(report.lookupFailed()));
        addList(embed, Glyph.BANNED + " Bad Discord values", report.invalidDiscord().stream()
                .map(p -> "Row " + p.row() + " **" + p.ign() + "**: \"" + p.value() + "\" " + p.reason()).toList());
        addList(embed, "👯 Duplicates", rows(report.duplicates()));
        addList(embed, Glyph.KILLS + " Conflicts", result.conflicts().stream()
                .map(c -> "**" + c.name() + "** (" + c.discordId() + "): " + c.reason()).toList());
        return embed.build();
    }

    /** Activity check result for the admin who ran it (and the preview, which sends nothing). */
    static MessageEmbed activitySummary(ActivityReport report, List<String> lines, boolean preview, int dmFailures) {
        String required = quotas(report);
        EmbedBuilder embed = base(report.below().isEmpty() ? SUCCESS : WARNING)
                .setTitle(preview ? Glyph.ACTIVITY + " Activity check preview" : Glyph.ACTIVITY + " Activity check done")
                .setDescription((preview ? "*Preview: nobody was warned.*\n" : "")
                        + "**" + report.below().size() + "** of " + report.checked() + " members played under their quota ("
                        + required + ") in the last " + report.windowDays() + " days.\n"
                        + (preview ? "Run `/activitycheck run` without `preview` to warn them."
                        : "They were warned by DM and have until the deadline to catch up, or they get a strike."
                        + (dmFailures > 0 ? "\n" + Glyph.WARN + " " + dmFailures + " DM(s) failed (DMs closed or not in the server)." : "")))
                .setFooter(skipped(report) + report.recentlyAdded().size() + " added in the last " + report.windowDays() + " days skipped");
        addList(embed, "Under their quota", lines);
        return embed.build();
    }

    /** "3 h for new members, 2 h for Original Supporters", or just "3 h" when originals aren't checked. */
    private static String quotas(ActivityReport report) {
        return report.originalMinSeconds() > 0
                ? formatHours(report.minSeconds()) + " for new members, " + formatHours(report.originalMinSeconds()) + " for Original Supporters"
                : formatHours(report.minSeconds());
    }

    private static String skipped(ActivityReport report) {
        return report.originalsSkipped() > 0 ? report.originalsSkipped() + " original members not checked · " : "";
    }

    /** The record posted to the log channel after warnings go out. */
    static MessageEmbed activityLog(ActivityReport report, List<String> lines, String admin, long deadline) {
        EmbedBuilder embed = base(report.below().isEmpty() ? SUCCESS : WARNING)
                .setTitle(Glyph.ACTIVITY + " Weekly activity check")
                .setDescription(report.below().isEmpty()
                        ? Glyph.YES + " Every member reached their quota (" + quotas(report) + ") this week. Nobody was warned."
                        : "**" + report.below().size() + "** of " + report.checked() + " members played under their quota ("
                        + quotas(report) + ") in the last " + report.windowDays() + " days.\n"
                        + Glyph.TIME + " New warnings have until " + timestamp(deadline, 'F') + " (" + timestamp(deadline, 'R')
                        + ") to catch up, or they get a strike. Enough strikes and they're removed for good.")
                .setFooter("Run by " + admin + " · " + skipped(report) + report.recentlyAdded().size() + " recently added skipped");
        addList(embed, "Players", lines);
        return embed.build();
    }

    static MessageEmbed weeklyRecap(WeeklyRecap.Data data) {
        EmbedBuilder embed = base(BRAND)
                .setTitle(Glyph.CALENDAR + " Weekly recap")
                .setDescription(timestamp(data.weekStart(), 'D') + " → " + timestamp(data.weekEnd(), 'D') + "\n\n"
                        + (data.activePlayers() == 0
                        ? "Nobody linked played this week."
                        : Glyph.TIME + " **" + formatPlaytime(data.totalSeconds()) + "** played by **" + data.activePlayers()
                        + "** players this week."))
                .setFooter(io.github.lrdrandomplayz.smplink.Branding.name() + " · every week");

        if (!data.playtime().isEmpty()) {
            embed.addField(Glyph.TIME + " Most playtime", ranked(data.playtime(), e -> formatPlaytime(e.value())), false);
        }
        if (data.statsSince() == 0) {
            embed.addField(Glyph.KILLS + " Kills & mining", "These start with next week's recap, once there's a week of stats to compare.", false);
        } else {
            addTop(embed, Glyph.KILLS + " Mob kills", data.mobKills());
            addTop(embed, Glyph.MINING + " Blocks mined", data.blocksMined());
            addTop(embed, Glyph.ASSASSIN + " Player kills", data.playerKills());
            addTop(embed, Glyph.DEATHS + " Deaths", data.deaths());
        }
        if (!data.milestones().isEmpty()) {
            List<String> lines = data.milestones().stream()
                    .map(m -> {
                        var link = data.linksByUuid().get(m.uuid());
                        return link == null ? null : Glyph.PARTY + " <@" + link.discordId() + "> · `" + link.name() + "` reached **" + m.hours() + " h**";
                    })
                    .filter(java.util.Objects::nonNull)
                    .toList();
            addList(embed, Glyph.MILESTONE + " Milestones reached", lines);
        }
        if (!data.newMembers().isEmpty()) {
            List<String> lines = data.newMembers().stream()
                    .map(link -> Glyph.WELCOME + " <@" + link.discordId() + "> · `" + link.name() + "`")
                    .toList();
            addList(embed, Glyph.NEW + " New members (" + data.newMembers().size() + ")", lines);
        }
        return embed.build();
    }

    private static void addTop(EmbedBuilder embed, String title, List<WeeklyRecap.Entry> entries) {
        if (!entries.isEmpty()) {
            embed.addField(title, ranked(entries, e -> number(e.value())), true);
        }
    }

    private static String ranked(List<WeeklyRecap.Entry> entries, java.util.function.Function<WeeklyRecap.Entry, String> value) {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            WeeklyRecap.Entry entry = entries.get(i);
            if (i > 0) {
                lines.append('\n');
            }
            lines.append(i < 3 ? MEDALS[i] : "`#" + (i + 1) + "`").append(" **").append(entry.link().name())
                    .append("** · ").append(value.apply(entry)).append("\n<@").append(entry.link().discordId()).append('>');
        }
        return lines.toString();
    }

    // --- shared pieces ----------------------------------------------------------------------------------

    /**
     * A progress bar for the playtime quota, e.g. {@code `██████░░░░░░` 52%}. Monospace so the blocks
     * line up the same on desktop and mobile.
     */
    static String quotaBar(long playedSeconds, long requiredSeconds, int width) {
        double fraction = requiredSeconds <= 0 ? 1 : Math.min(1, (double) playedSeconds / requiredSeconds);
        int filled = (int) Math.round(fraction * width);
        return "`" + "█".repeat(filled) + "░".repeat(width - filled) + "` " + Math.round(fraction * 100) + "%";
    }

    /** Where a rolling window's totals actually start counting: its start, or when recording began if that's later. */
    static long windowStart(long trackingStarted, int windowDays) {
        return Math.max(System.currentTimeMillis() - windowDays * 24L * 60 * 60 * 1000, trackingStarted);
    }

    static String formatHours(long seconds) {
        double hours = seconds / 3600.0;
        return (hours == Math.rint(hours) ? String.valueOf((long) hours) : String.format(Locale.ROOT, "%.1f", hours)) + " h";
    }

    static String formatPlaytime(long seconds) {
        long minutes = seconds / 60;
        return number(minutes / 60) + " h " + (minutes % 60) + " m";
    }

    /** Adds one or more fields for a list, splitting at the field limit and stopping before the embed limit. */
    static void addList(EmbedBuilder embed, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        StringBuilder chunk = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).length() > 200 ? lines.get(i).substring(0, 197) + "..." : lines.get(i);
            boolean full = embed.length() + chunk.length() + line.length() > LIST_BUDGET
                    || embed.getFields().size() >= MessageEmbed.MAX_FIELD_AMOUNT - 1;
            if (full) {
                String more = "…and " + (lines.size() - i) + " more";
                if (chunk.length() + more.length() + 1 > FIELD_LIMIT) {
                    embed.addField(first ? title : title + " (cont.)", chunk.toString(), false);
                    chunk.setLength(0);
                    first = false;
                }
                chunk.append(chunk.isEmpty() ? "" : "\n").append(more);
                break;
            }
            if (chunk.length() + line.length() + 1 > FIELD_LIMIT) {
                embed.addField(first ? title : title + " (cont.)", chunk.toString(), false);
                chunk.setLength(0);
                first = false;
            }
            chunk.append(chunk.isEmpty() ? "" : "\n").append(line);
        }
        if (!chunk.isEmpty()) {
            embed.addField(first ? title : title + " (cont.)", chunk.toString(), false);
        }
    }

    private static List<String> rows(List<Problem> problems) {
        return problems.stream().map(p -> "Row " + p.row() + " **" + p.ign() + "**: " + p.reason()).toList();
    }

    private static String slotLine(String label, ItemView item) {
        return "`" + label + "` " + describe(item);
    }

    private static String describe(ItemView item) {
        StringBuilder text = new StringBuilder(item.amount() + "× " + titleCase(item.material()));
        if (item.displayName() != null) {
            text.append(" \"").append(item.displayName()).append('"');
        }
        if (!item.enchants().isEmpty()) {
            text.append(" (").append(item.enchants().entrySet().stream()
                    .map(e -> titleCase(e.getKey()) + " " + roman(e.getValue()))
                    .collect(Collectors.joining(", "))).append(')');
        }
        return text.toString();
    }

    /** "\n🛡️ Middleman **+5** · 6 trades" for members who have middlemanned, otherwise nothing. */
    static String middlemanRep(TradeRepository.Rep rep) {
        if (rep == null || (rep.trades() == 0 && rep.vouches() == 0 && rep.scamReports() == 0)) {
            return "";
        }
        int score = rep.score();
        return "\n" + Glyph.SHIELD + " Middleman **" + (score > 0 ? "+" : "") + score + "**" + (score < 0 ? " " + Glyph.WARN : "") + " · "
                + rep.trades() + (rep.trades() == 1 ? " trade" : " trades");
    }

    static String titleCase(String id) {
        return Arrays.stream(id.toLowerCase(Locale.ROOT).split("[_\\s]+"))
                .filter(word -> !word.isEmpty())
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                .collect(Collectors.joining(" "));
    }

    private static String roman(int n) {
        if (n < 1 || n > 30) {
            return String.valueOf(n);
        }
        int[] values = {10, 9, 5, 4, 1};
        String[] symbols = {"X", "IX", "V", "IV", "I"};
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            while (n >= values[i]) {
                out.append(symbols[i]);
                n -= values[i];
            }
        }
        return out.toString();
    }

    /** Skin images from mc-heads.net by name: this server gives premium players offline UUIDs, which have no skin. */
    static String skin(String kind, String name) {
        String key = URLEncoder.encode(name == null ? "MHF_Steve" : name, StandardCharsets.UTF_8);
        return "avatar".equals(kind) ? "https://mc-heads.net/avatar/" + key + "/128" : "https://mc-heads.net/body/" + key;
    }

    static String timestamp(Long ms, char style) {
        return ms == null || ms == 0 ? "never" : "<t:" + (ms / 1000) + ":" + style + ">";
    }

    static String number(double value) {
        return value == Math.rint(value)
                ? String.format(Locale.US, "%,d", (long) value)
                : String.format(Locale.US, "%,.1f", value);
    }

    private static String orUnknown(String name) {
        return name == null ? "Unknown player" : name;
    }
}
