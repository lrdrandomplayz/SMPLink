package io.github.lrdrandomplayz.smplink.discord;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import io.github.lrdrandomplayz.smplink.PluginSettings;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.RemovedMember;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.Strike;
import io.github.lrdrandomplayz.smplink.http.SyncBridge;
import io.github.lrdrandomplayz.smplink.service.PlayerService;
import io.github.lrdrandomplayz.smplink.sync.SheetEditor;
import io.github.lrdrandomplayz.smplink.sync.SheetEditor.Match;
import io.github.lrdrandomplayz.smplink.sync.SheetImporter;

/**
 * Gives and takes back strikes, whatever their source: the member is DMed, moderators get a post, and
 * the strike that reaches the member's limit removes them for good (deleted from the sheet, which the
 * sync that follows turns into whitelist removal and unlinking, and blocked from /whitelist add).
 */
final class StrikeService {

    /** Who gets the strike. */
    record Target(String discordId, UUID uuid, String ign) {
    }

    /**
     * What happened: the new strike's ID, the member's active strikes and limit, whether they were
     * removed, and anything that went wrong (a failed DM, or a removal that needs an admin).
     */
    record Given(long id, List<Strike> active, int limit, boolean removed, String dmFailure, String problem) {
    }

    record Taken(Strike strike, List<Strike> active, int limit, Optional<RemovedMember> removal, String dmFailure) {
    }

    private final PluginSettings settings;
    private final TrackingRepository tracking;
    private final Strikes strikes;
    private final SheetEditor editor;
    private final SheetImporter importer;
    private final Logger logger;
    // Used instead of the sheet when linking.mode is codes; null in sheet mode.
    private Roster roster;

    void setRoster(Roster roster) {
        this.roster = roster;
    }

    StrikeService(PluginSettings settings, TrackingRepository tracking, Strikes strikes, SheetEditor editor,
                  SheetImporter importer, Logger logger) {
        this.settings = settings;
        this.tracking = tracking;
        this.strikes = strikes;
        this.editor = editor;
        this.importer = importer;
        this.logger = logger;
    }

    /**
     * Gives a strike. {@code strikeDm} and {@code removedDm} are the DM templates, with {player} and any
     * other placeholders of their own already filled in; {@code extra} fields go on the moderator post.
     */
    Given give(JDA jda, Target target, String kind, String reason, String givenBy, Long warningId, long givenAt,
               String strikeDm, String removedDm, List<MessageEmbed.Field> extra) throws Exception {
        long id = SyncBridge.await(tracking.addStrike(target.discordId(), target.uuid(), target.ign(), kind, reason,
                givenBy, warningId, givenAt), PlayerService.DB_TIMEOUT_MS);
        List<Strike> active = strikes.active(target.discordId());
        int limit = strikes.limit(jda, target.discordId());
        int count = strikes.weighted(active);
        boolean reachesLimit = count >= limit;

        String problem = null;
        if (reachesLimit) {
            SyncBridge.await(tracking.recordRemoval(target.discordId(), target.uuid(), target.ign(), System.currentTimeMillis()),
                    PlayerService.DB_TIMEOUT_MS);
            problem = removeFromSheet(target, kind);
        }
        String template = reachesLimit ? removedDm : strikeDm;
        String dmFailure = ActivityCheck.sendDm(jda, target.discordId(), strikes.fill(template
                .replace("{player}", target.ign())
                .replace("{reason}", reason)
                .replace("{id}", String.valueOf(id)), count, limit) + (strikes.countsNote(kind).isEmpty() ? "" : "\n" + strikes.countsNote(kind)));
        announce(jda, target, id, kind, reason, givenBy, active, limit, reachesLimit, dmFailure, problem, extra);
        logger.info("[strikes] #" + id + " (" + kind + ") for " + target.ign() + " (" + target.discordId() + ") by "
                + givenBy + ": " + reason + " · now " + count + "/" + limit
                + (reachesLimit ? " · permanently removed" + (problem == null ? "" : " (" + problem + ")") : ""));
        if (reachesLimit && problem == null && importer != null) {
            // The sync unlinks them, runs the whitelist remove command and takes the linked role.
            importer.runFresh("strike #" + id + " removal").exceptionally(error -> {
                logger.log(Level.WARNING, "[strikes] sync after the removal of " + target.ign() + " failed", error);
                return null;
            });
        }
        return new Given(id, active, limit, reachesLimit, dmFailure, problem);
    }

    /** Strikes' worth of these active strikes (raid strikes weigh more); this is what is compared with the limit. */
    int weighted(List<Strike> active) {
        return strikes.weighted(active);
    }

    /** "(counts as 2)" for a raid strike, otherwise empty. */
    String countsNote(String kind) {
        return strikes.countsNote(kind);
    }

    /** A strike by its ID, removed or not. */
    Optional<Strike> find(long id) throws Exception {
        return SyncBridge.await(tracking.findStrike(id), PlayerService.DB_TIMEOUT_MS);
    }

    /** A member's strikes that still count, oldest first. */
    List<Strike> active(String discordId) throws Exception {
        return strikes.active(discordId);
    }

    /** Takes a strike back. Empty if there's no such strike or it was already removed. */
    Optional<Taken> remove(JDA jda, long id, String removedBy) throws Exception {
        Optional<Strike> found = SyncBridge.await(tracking.findStrike(id), PlayerService.DB_TIMEOUT_MS);
        if (found.isEmpty() || found.get().removedAt() != null
                || !SyncBridge.await(tracking.removeStrike(id, removedBy, System.currentTimeMillis()), PlayerService.DB_TIMEOUT_MS)) {
            return Optional.empty();
        }
        Strike strike = found.get();
        List<Strike> active = strikes.active(strike.discordId());
        int limit = strikes.limit(jda, strike.discordId());
        Optional<RemovedMember> removal = SyncBridge.await(tracking.findRemoval(strike.discordId(), null), PlayerService.DB_TIMEOUT_MS);
        String dmFailure = removal.isPresent() ? null : ActivityCheck.sendDm(jda, strike.discordId(),
                "Hi " + strike.ign() + ", strike #" + strike.id() + " on " + io.github.lrdrandomplayz.smplink.Branding.name() + " (" + strike.reason() + ") was removed by staff. "
                        + "You now have **" + strikes.weighted(active) + " of " + limit + "** strikes.");
        GuildMessageChannel channel = Channels.find(jda, settings.activityRemovalChannelId(), "activity-check.removal-channel-id", logger);
        if (channel != null) {
            channel.sendMessageEmbeds(Embeds.base(Embeds.SUCCESS)
                            .setTitle(Glyph.UNDO + " Strike #" + strike.id() + " removed")
                            .setThumbnail(Embeds.skin("avatar", strike.ign()))
                            .setDescription("<@" + strike.discordId() + "> · `" + strike.ign() + "` now has **" + strikes.weighted(active)
                                    + " of " + limit + "** strikes.\n" + Strikes.describe(strike)
                                    + (removal.isPresent() ? "\n" + Glyph.WARN + " They were already permanently removed. Taking a strike back doesn't "
                                    + "re-add them; add them to the sheet by hand if they should come back." : "")
                                    + (dmFailure == null ? "" : "\n" + Glyph.WARN + " Their DM failed (" + dmFailure + ")."))
                            .addField("Given", "<t:" + strike.givenAt() / 1000 + ":f> by " + strike.givenBy(), true)
                            .addField("Removed by", removedBy, true)
                            .build())
                    .setAllowedMentions(List.of())
                    .queue(null, error -> logger.warning("[strikes] could not post the removal of #" + id + ": " + error.getMessage()));
        }
        logger.info("[strikes] #" + id + " for " + strike.ign() + " removed by " + removedBy);
        return Optional.of(new Taken(strike, active, limit, removal, dmFailure));
    }

    /** Null when the row was deleted (or wasn't there), otherwise why it couldn't be. */
    private String removeFromSheet(Target target, String kind) {
        if (editor == null && roster != null) {
            return roster.remove(target.discordId(), target.ign()).problem();
        }
        if (editor == null) {
            return "sheet sync is off, so they couldn't be removed; remove them by hand";
        }
        try {
            Match match = editor.find(target.discordId(), target.ign());
            if (match == null) {
                return null;
            }
            // Original members are never activity-checked, so an activity strike removing one means the sheet
            // changed under us; other strikes apply to everyone.
            if (match.table() == 0 && kind.equals("activity")) {
                return "they're in the original members' table now, so they were not removed";
            }
            editor.remove(match.row(), match.ign());
            return null;
        } catch (Exception e) {
            return "the sheet couldn't be updated (" + e.getMessage() + "); remove them by hand";
        }
    }

    /** One message per strike in the moderator channel. */
    private void announce(JDA jda, Target target, long id, String kind, String reason, String givenBy, List<Strike> active,
                          int limit, boolean removal, String dmFailure, String problem, List<MessageEmbed.Field> extra) {
        GuildMessageChannel channel = Channels.find(jda, settings.activityRemovalChannelId(),
                "activity-check.removal-channel-id", logger);
        if (channel == null) {
            return;
        }
        StringBuilder history = new StringBuilder();
        for (Strike strike : active) {
            history.append(history.isEmpty() ? "" : "\n").append(Strikes.describe(strike)).append(" · <t:")
                    .append(strike.givenAt() / 1000).append(":D>");
        }
        EmbedBuilder embed = Embeds.base(removal ? Embeds.DANGER : Embeds.WARNING)
                .setTitle(removal ? Glyph.BANNED + " Permanently removed · strike #" + id : Glyph.ALERT + " Strike #" + id
                        + (strikes.countsNote(kind).isEmpty() ? "" : " " + strikes.countsNote(kind)) + " · " + strikes.weighted(active) + " of " + limit)
                .setThumbnail(Embeds.skin("avatar", target.ign()))
                .setDescription("<@" + target.discordId() + "> · `" + target.ign() + "`\n" + Strikes.kindEmoji(kind) + " **" + reason
                        + "**\nGiven by " + givenBy
                        + (dmFailure == null ? "" : "\n" + Glyph.WARN + " Their DM failed (" + dmFailure + ").")
                        + (problem == null ? "" : "\n" + Glyph.WARN + " **Needs an admin:** " + problem));
        extra.forEach(embed::addField);
        embed.addField(Glyph.ALERT + " Active strikes (" + strikes.weighted(active) + "/" + limit + ")", history.toString(), false)
                .setFooter(removal
                        ? "Deleted from the sheet, removed from the whitelist, unlinked and blocked from /whitelist add"
                        : "Strikes last " + strikes.expiryText() + ". Take one back with /strike remove");
        channel.sendMessageEmbeds(embed.build())
                .setAllowedMentions(List.of())
                .queue(null, error -> logger.warning("[strikes] could not post strike #" + id + ": " + error.getMessage()));
    }
}
