package io.github.lrdrandomplayz.smplink.discord;

import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import io.github.lrdrandomplayz.smplink.db.LinkRepository.Link;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.Warning;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.WarningRecord;
import io.github.lrdrandomplayz.smplink.http.SyncBridge;
import io.github.lrdrandomplayz.smplink.service.PlayerService;
import io.github.lrdrandomplayz.smplink.service.PlayerService.Standing;
import io.github.lrdrandomplayz.smplink.service.PlayerService.Stats;
import io.github.lrdrandomplayz.smplink.sync.SheetEditor;
import io.github.lrdrandomplayz.smplink.sync.SheetEditor.Match;

/** /lookup: one private staff card with a member's link, sheet row, playtime and activity-check history. */
final class LookupCommand {

    private final PlayerService players;
    private final TrackingRepository tracking;
    private final SheetEditor editor;
    private final ActivityCheck activityCheck;
    private final Strikes strikes;
    private final Logger logger;

    LookupCommand(PlayerService players, TrackingRepository tracking, SheetEditor editor, ActivityCheck activityCheck,
                  Strikes strikes,
                  Logger logger) {
        this.players = players;
        this.tracking = tracking;
        this.editor = editor;
        this.activityCheck = activityCheck;
        this.strikes = strikes;
        this.logger = logger;
    }

    static SlashCommandData definition() {
        return Commands.slash("lookup", "Everything about a member (whitelist staff only)")
                .addOption(OptionType.USER, "discord", "Their Discord account", false)
                .addOption(OptionType.STRING, "ign", "Their Minecraft username", false);
    }

    /** Runs on a worker thread after an ephemeral defer. */
    void handle(InteractionHook hook, User member, String ign) throws Exception {
        if (member == null && (ign == null || ign.isBlank())) {
            hook.editOriginal("Give either `discord` or `ign` to say who to look up.").queue();
            return;
        }
        Optional<Link> link;
        if (member != null) {
            try {
                link = Optional.of(players.link(member.getId()));
            } catch (Exception notLinked) {
                link = Optional.empty();
            }
        } else {
            link = players.linkByName(ign.trim());
        }
        String discordId = link.map(Link::discordId).orElse(member == null ? null : member.getId());

        Match row = null;
        if (editor != null) {
            try {
                row = editor.find(discordId, link.map(Link::name).orElse(ign));
            } catch (Exception e) {
                logger.log(Level.WARNING, "/lookup couldn't read the sheet", e);
            }
        }
        if (link.isEmpty() && row == null) {
            hook.editOriginal((member != null ? member.getAsMention() : "`" + ign + "`")
                    + " isn't linked and isn't in the sheet.").setAllowedMentions(List.of()).queue();
            return;
        }

        EmbedBuilder embed = Embeds.base(Embeds.BRAND);
        String name = link.map(Link::name).orElse(row == null ? ign : row.ign());
        embed.setTitle(Glyph.SEARCH + " " + (name == null ? "Member lookup" : name));
        if (name != null) {
            embed.setThumbnail(Embeds.skin("avatar", name));
        }

        StringBuilder who = new StringBuilder();
        if (discordId != null) {
            who.append("Discord: <@").append(discordId).append("> (`").append(discordId).append("`)\n");
        }
        if (link.isPresent()) {
            who.append("UUID: `").append(link.get().uuid()).append("`\n")
                    .append(link.get().original() ? "Original member" : "New member")
                    .append(" · linked <t:").append(link.get().linkedAt() / 1000).append(":D>");
        } else {
            who.append(Glyph.WARN + " Not linked yet (the next sync links them if their row is valid)");
        }
        embed.setDescription(who);

        embed.addField(Glyph.NOTE + " Sheet", row == null
                ? (editor == null ? "Sheet sync is off" : "Not in the sheet")
                : "Row " + row.row() + " · " + (row.table() == 0 ? "original members" : "new members") + " table", true);

        if (link.isPresent()) {
            Stats stats = players.stats(link.get().discordId());
            Standing standing = players.playtime(link.get().discordId());
            embed.addField(Glyph.SEARCH + " Status", stats.online() ? Glyph.ONLINE + " Online" : "Last seen " + (stats.lastSeen() == null
                    ? "never" : "<t:" + stats.lastSeen() / 1000 + ":R>"), true);
            embed.addField(Glyph.TIME + " Playtime", "All time **" + Embeds.formatPlaytime(standing.playtimeSeconds()) + "** (#"
                    + standing.rank() + " of " + standing.totalRanked() + ")\nLast 7 days "
                    + Embeds.formatPlaytime(standing.weekSeconds()) + "\nThis month "
                    + Embeds.formatPlaytime(standing.monthSeconds()), false);

            long minSeconds = activityCheck.minSeconds(link.get().original());
            if (minSeconds > 0) {
                Optional<Warning> open = SyncBridge.await(tracking.pendingWarning(link.get().discordId()), PlayerService.DB_TIMEOUT_MS);
                String activity;
                if (open.isPresent()) {
                    Warning warning = open.get();
                    long played = players.secondsPlayedBetween(link.get().uuid(), warning.windowStart(),
                            Math.min(System.currentTimeMillis(), warning.deadline()));
                    activity = Glyph.WARN + " **Open warning** · deadline <t:" + warning.deadline() / 1000 + ":R>\n"
                            + Embeds.quotaBar(played, minSeconds, 14) + " · " + Embeds.formatPlaytime(played) + " of "
                            + Embeds.formatHours(minSeconds) + " · **" + Embeds.formatPlaytime(Math.max(0, minSeconds - played))
                            + " left**";
                } else {
                    activity = "No open warning\n" + Embeds.quotaBar(standing.weekSeconds(), minSeconds, 14) + " · "
                            + Embeds.formatPlaytime(standing.weekSeconds()) + " of " + Embeds.formatHours(minSeconds)
                            + " this week";
                }
                List<WarningRecord> history = SyncBridge.await(tracking.warningHistory(link.get().discordId()),
                        PlayerService.DB_TIMEOUT_MS);
                long passed = history.stream().filter(r -> r.status().equals("passed")).count();
                activity += "\n" + strikes.line(strikes.active(link.get().discordId()),
                        strikes.limit(hook.getJDA(), link.get().discordId()));
                activity += "\nWarned " + history.size() + " time(s), passed " + passed;
                if (!history.isEmpty()) {
                    activity += " · last warned <t:" + history.get(0).warnedAt() / 1000 + ":D>";
                }
                embed.addField(Glyph.ACTIVITY + " Activity check", activity, false);
            }
        } else if (discordId != null) {
            List<WarningRecord> history = SyncBridge.await(tracking.warningHistory(discordId), PlayerService.DB_TIMEOUT_MS);
            if (!history.isEmpty()) {
                embed.addField(Glyph.ACTIVITY + " Activity check", "Warned " + history.size() + " time(s); last outcome: "
                        + history.get(0).status(), false);
            }
        }
        hook.editOriginalEmbeds(embed.build()).setAllowedMentions(List.of()).queue();
    }
}
