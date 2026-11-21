package io.github.lrdrandomplayz.smplink.discord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import io.github.lrdrandomplayz.smplink.PluginSettings;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.Warning;
import io.github.lrdrandomplayz.smplink.http.SyncBridge;
import io.github.lrdrandomplayz.smplink.service.PlayerService;
import io.github.lrdrandomplayz.smplink.service.PlayerService.ActivityEntry;
import io.github.lrdrandomplayz.smplink.service.PlayerService.ActivityReport;

/**
 * Warns new members below the weekly playtime minimum: each gets a DM with exactly how much more they
 * must play and a deadline, and the warning is recorded for {@link ActivityEnforcer} to follow up. Used
 * by /activitycheck and the weekly schedule. Blocks on Discord, so run it on a worker thread.
 */
final class ActivityCheck {

    static final int WINDOW_DAYS = 7;
    static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** {@code notReady} is set, and nothing was sent, when too little playtime has been recorded yet. */
    record Outcome(ActivityReport report, List<String> lines, int failures, String notReady) {
    }

    private final PlayerService players;
    private final TrackingRepository tracking;
    private final PluginSettings settings;
    private final Strikes strikes;
    private final Logger logger;
    private final ActivityPause pause;

    ActivityCheck(PlayerService players, TrackingRepository tracking, PluginSettings settings, Strikes strikes, ActivityPause pause,
                  Logger logger) {
        this.pause = pause;
        this.strikes = strikes;
        this.players = players;
        this.tracking = tracking;
        this.settings = settings;
        this.logger = logger;
    }

    /** New members' weekly quota. */
    long minSeconds() {
        return Math.round(settings.activityMinHours() * 3600);
    }

    /** The weekly quota for a member: Original Supporters have their own (0 = not checked). */
    long minSeconds(boolean original) {
        return original ? Math.round(settings.activityOriginalMinHours() * 3600) : minSeconds();
    }

    ActivityPause pause() {
        return pause;
    }

    /**
     * {@code force} checks even with less than a week of recorded playtime and includes members added to
     * the sheet during the week, who are otherwise skipped. {@code preview} only lists who would be warned.
     */
    synchronized Outcome run(JDA jda, String runBy, boolean preview, boolean force) throws Exception {
        ActivityReport report = players.activityReport(WINDOW_DAYS, minSeconds(false), minSeconds(true), force, pause.seasonStart());

        if (pause.isPaused() && !preview) {
            long until = pause.until();
            return new Outcome(report, List.of(), 0, "Activity checks are paused" + (until > 0 ? " until <t:" + until / 1000 + ":F>" : "")
                    + (pause.pausedBy().isEmpty() ? "" : " (by " + pause.pausedBy() + ")") + ", so nobody was checked.");
        }
        if (!report.fullWindowTracked() && !force && !preview) {
            long readyAt = report.trackingStarted() + WINDOW_DAYS * DAY_MS;
            String message = "Playtime has only been counted since <t:" + report.trackingStarted() / 1000 + ":f>"
                    + (report.trackingStarted() == pause.seasonStart() ? " (the start of this season)" : "")
                    + ", so everyone's weekly total is still incomplete and members would be warned for playing "
                    + "too little. A full week is available <t:" + readyAt / 1000 + ":R>.";
            return new Outcome(report, List.of(), 0, message);
        }

        Map<String, Warning> pending = new HashMap<>();
        for (Warning warning : SyncBridge.await(tracking.pendingWarnings(), PlayerService.DB_TIMEOUT_MS)) {
            pending.put(warning.discordId(), warning);
        }

        long now = System.currentTimeMillis();
        long deadline = now + settings.activityGraceDays() * DAY_MS;
        List<String> lines = new ArrayList<>();
        int failures = 0;
        logger.info("[activity] " + (preview ? "preview" : "check") + (force ? " (forced)" : "") + " by " + runBy + ": "
                + report.below().size() + " of " + report.checked() + " members under their quota");
        int exempt = 0;
        for (ActivityEntry entry : report.below()) {
            if (strikes.exemptFromActivity(jda, entry.link().discordId())) {
                exempt++;
                continue;
            }
            long needed = entry.required() - entry.seconds();
            String line = "**" + entry.link().name() + "** · <@" + entry.link().discordId() + ">" + (entry.link().original() ? " · Original" : "") + "\n"
                    + Embeds.quotaBar(entry.seconds(), entry.required(), 10) + " · needs " + Embeds.formatPlaytime(needed) + " more";
            Warning existing = pending.get(entry.link().discordId());
            if (existing != null) {
                lines.add(line + " · already warned, deadline <t:" + existing.deadline() / 1000 + ":R>");
                continue;
            }
            if (preview) {
                lines.add(line);
                continue;
            }
            int current = strikes.weighted(strikes.active(entry.link().discordId()));
            int limit = strikes.limit(jda, entry.link().discordId());
            String failure = sendDm(jda, entry.link().discordId(), strikes.fill(settings.activityDmMessage()
                    .replace("{player}", entry.link().name())
                    .replace("{playtime}", Embeds.formatPlaytime(entry.seconds()))
                    .replace("{required}", Embeds.formatHours(entry.required()))
                    .replace("{needed}", Embeds.formatPlaytime(needed))
                    .replace("{deadline}", "<t:" + deadline / 1000 + ":F> (<t:" + deadline / 1000 + ":R>)"), current, limit));
            // Recorded even if the DM bounced: the deadline still applies, and the log shows who wasn't told.
            SyncBridge.await(tracking.addWarning(entry.link().discordId(), entry.link().uuid(), entry.link().name(),
                    report.windowStart(), now, deadline), PlayerService.DB_TIMEOUT_MS);
            if (failure == null) {
                line += " · warned" + (current > 0 ? " · " + Glyph.ALERT + " " + current + "/" + limit + " strikes" : "");
                logger.info("[activity] warned discord=" + entry.link().discordId() + " uuid=" + entry.link().uuid()
                        + ": needs " + needed / 60 + " more minutes");
            } else {
                failures++;
                line += " · DM failed (" + failure + "), deadline still applies";
                logger.warning("[activity] warning DM to discord=" + entry.link().discordId() + " failed: " + failure);
            }
            lines.add(line);
        }

        if (exempt > 0) {
            lines.add(Glyph.SHIELD + " " + exempt + " more under the minimum but skipped: they're the server owner, an admin, or have an exempt role");
        }
        if (!preview) {
            GuildMessageChannel channel = Channels.find(jda, settings.activityLogChannelId(), "activity-check.log-channel-id", logger);
            if (channel != null) {
                channel.sendMessageEmbeds(Embeds.activityLog(report, lines, runBy, deadline))
                        .setAllowedMentions(List.of())
                        .queue(null, error -> logger.warning("[activity] could not post to the log channel: " + error.getMessage()));
            }
        }
        return new Outcome(report, lines, failures, null);
    }

    /** Returns null on success, or a short reason the DM could not be delivered. */
    static String sendDm(JDA jda, String discordId, String message) {
        try {
            User user = jda.retrieveUserById(discordId).complete();
            user.openPrivateChannel().complete().sendMessage(message).complete();
            return null;
        } catch (ErrorResponseException e) {
            return switch (e.getErrorCode()) {
                case 50007 -> "they don't accept DMs from the bot";
                case 10013 -> "unknown Discord user";
                default -> e.getMeaning();
            };
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }
}
