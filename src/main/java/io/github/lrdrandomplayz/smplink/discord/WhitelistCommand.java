package io.github.lrdrandomplayz.smplink.discord;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import io.github.lrdrandomplayz.smplink.PluginSettings;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.RemovedMember;
import io.github.lrdrandomplayz.smplink.http.SyncBridge;
import io.github.lrdrandomplayz.smplink.service.PlayerService;
import io.github.lrdrandomplayz.smplink.sync.SheetEditor;
import io.github.lrdrandomplayz.smplink.sync.SheetEditor.Added;
import io.github.lrdrandomplayz.smplink.sync.SheetEditor.EditException;
import io.github.lrdrandomplayz.smplink.sync.SheetEditor.Match;
import io.github.lrdrandomplayz.smplink.sync.SheetImporter;
import io.github.lrdrandomplayz.smplink.sync.SheetImporter.Report;

/**
 * /whitelist add and /whitelist remove edit the Google Sheet, then run a sync so linking, the
 * whitelist, the role and the nickname follow from the sheet exactly as they do for manual edits.
 */
final class WhitelistCommand {

    // "wlremove:<admin>:<row>:<ign>" confirms a removal; "wlcancel:<admin>" dismisses it.
    private static final String CONFIRM_PREFIX = "wlremove:";
    private static final String CANCEL_PREFIX = "wlcancel:";
    // "wladd:<admin>:<discord>:<edition>:<donor 0/1>:<ign>" adds a player the activity check removed, after a warning.
    private static final String READD_PREFIX = "wladd:";

    private final SheetEditor editor;
    private final SheetImporter importer;
    private final TrackingRepository tracking;
    private final PluginSettings settings;
    private final Logger logger;
    // Used instead of the sheet when linking.mode is codes; null in sheet mode.
    private Roster roster;

    WhitelistCommand(SheetEditor editor, SheetImporter importer, TrackingRepository tracking, PluginSettings settings,
                     Logger logger) {
        this.tracking = tracking;
        this.settings = settings;
        this.editor = editor;
        this.importer = importer;
        this.logger = logger;
    }

    void setRoster(Roster roster) {
        this.roster = roster;
    }

    static SlashCommandData definition() {
        return Commands.slash("whitelist", "Add or remove a member in the sheet and whitelist (whitelist staff only)")
                .addSubcommands(
                        new SubcommandData("add", "Add a new member to the sheet, link them and whitelist them")
                                .addOption(OptionType.USER, "discord", "Their Discord account", true)
                                .addOptions(new OptionData(OptionType.STRING, "version", "Their Minecraft account type", true)
                                        .addChoice("Premium", "premium")
                                        .addChoice("Cracked", "cracked"))
                                .addOption(OptionType.STRING, "ign", "Their Minecraft username", true),
                        new SubcommandData("remove", "Remove a member from the sheet, unlink them and un-whitelist them")
                                .addOption(OptionType.USER, "discord", "Their Discord account", false)
                                .addOption(OptionType.STRING, "ign", "Their Minecraft username", false));
    }

    static boolean isButton(String id) {
        return id.startsWith(CONFIRM_PREFIX) || id.startsWith(CANCEL_PREFIX) || id.startsWith(READD_PREFIX);
    }

    /** Runs on a worker thread after the reply was deferred (ephemeral). True if they were added to the sheet. */
    boolean add(InteractionHook hook, User admin, User member, String edition, String ign, boolean donor) {
        return add(hook, admin, member, edition, ign, donor, false);
    }

    /**
     * As above; {@code returning} lets in a player the activity check removed (an accepted join application, or
     * "Add anyway" after the warning). Their old removal and still-active strikes are cleared, so they start fresh.
     */
    boolean add(InteractionHook hook, User admin, User member, String edition, String ign, boolean donor, boolean returning) {
        if (editor == null && roster == null) {
            hook.editOriginal("Sheet sync is turned off, so there's no sheet to add to.").queue();
            return false;
        }
        Optional<RemovedMember> removed;
        try {
            removed = SyncBridge.await(tracking.findRemoval(member.getId(), ign), PlayerService.DB_TIMEOUT_MS);
            if (removed.isPresent() && !returning) {
                RemovedMember r = removed.get();
                hook.editOriginalEmbeds(Embeds.base(Embeds.WARNING)
                                .setTitle(Glyph.ALERT + " Are you sure?")
                                .setThumbnail(Embeds.skin("avatar", r.ign()))
                                .setDescription(member.getAsMention() + " (`" + r.ign() + "`) was **banned before** by the activity check on "
                                        + "<t:" + r.removedAt() / 1000 + ":D> (<t:" + r.removedAt() / 1000 + ":R>).\n\n"
                                        + "**Why:** " + bannedReason(r) + "\n\n"
                                        + "Adding them anyway gives them a fresh start: the old ban and their strikes are cleared.")
                                .build())
                        .setComponents(ActionRow.of(
                                Button.danger(READD_PREFIX + admin.getId() + ":" + member.getId() + ":" + edition + ":" + (donor ? 1 : 0)
                                        + ":" + ign, "Add anyway").withEmoji(Glyph.PLUS.emoji()),
                                Button.secondary(CANCEL_PREFIX + admin.getId(), "Cancel")))
                        .setAllowedMentions(List.of()).queue();
                return false;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "/whitelist add removal check failed", e);
            hook.editOriginal("Couldn't check the removal list right now. Try again in a moment.").queue();
            return false;
        }
        if (editor == null) {
            return addWithoutSheet(hook, admin, member, edition, ign, donor, removed.isPresent());
        }
        Added added;
        try {
            added = editor.addMember(member.getId(), edition, ign, donor);
        } catch (EditException e) {
            hook.editOriginal(e.getMessage()).queue();
            return false;
        } catch (Exception e) {
            logger.log(Level.WARNING, "/whitelist add failed", e);
            hook.editOriginal("Couldn't add them to the sheet: " + e.getMessage()).queue();
            return false;
        }
        if (removed.isPresent()) {
            freshStart(member.getId(), ign, admin.getName());
        }
        logger.info("[whitelist] " + admin.getName() + " added " + added.ign() + " (" + added.edition()
                + (donor ? ", donor" : "") + ", discord=" + member.getId() + ") to the sheet, row " + added.row());
        String donorNote = (donor ? giveDonorRole(hook.getJDA(), member) : "")
                + (added.noDonorColumn() ? "\n" + Glyph.WARN + " The new members' table has no \"Donor?\" column, so the sheet doesn't say Donor." : "");
        audit(hook.getJDA(), Embeds.SUCCESS, Glyph.PLUS + " Member added", admin, member.getId(), added.ign(),
                added.edition() + (donor ? " · " + Glyph.GEM + " Donor" : "") + " · sheet row " + added.row());
        String welcome = sendWelcome(hook.getJDA(), member, added);
        hook.editOriginalEmbeds(Embeds.base(Embeds.BRAND)
                .setTitle(Glyph.EDIT + " Added to the sheet")
                .setDescription("`" + added.ign() + "` (" + added.edition() + ") for " + member.getAsMention()
                        + " is on row " + added.row() + ". Linking and whitelisting now…")
                .build()).queue();

        syncThen(hook, "/whitelist add by " + admin.getName(), report -> {
            boolean linked = report.result().changes().stream()
                    .anyMatch(change -> change.discordId().equals(member.getId()));
            boolean whitelisted = report.whitelist() != null
                    && report.whitelist().added().stream().anyMatch(name -> name.equalsIgnoreCase(added.ign()));
            StringBuilder text = new StringBuilder("`" + added.ign() + "` (" + added.edition() + ") for "
                    + member.getAsMention() + " is on row " + added.row() + " of the sheet.\n");
            text.append(linked ? Glyph.YES + " Linked" : Glyph.WARN + " Not linked yet (see /sync for why)").append('\n');
            if (report.whitelist() == null) {
                text.append("Whitelist sync is off.");
            } else if (report.whitelist().error() != null) {
                text.append(Glyph.WARN + " Whitelist: ").append(report.whitelist().error());
            } else {
                text.append(whitelisted ? Glyph.YES + " Whitelisted" : Glyph.YES + " Already whitelisted");
            }
            text.append('\n').append(welcome);
            if (!donorNote.isEmpty()) {
                text.append('\n').append(donorNote.strip());
            }
            return Embeds.base(linked ? Embeds.SUCCESS : Embeds.BRAND).setTitle(Glyph.YES + " Member added")
                    .setDescription(text).build();
        });
        return true;
    }

    /** linking.mode: codes. Whitelists the name now; the player links themselves with /link. */
    private boolean addWithoutSheet(InteractionHook hook, User admin, User member, String edition, String ign, boolean donor,
                                    boolean returning) {
        String problem = roster.whitelist(ign);
        if (returning) {
            freshStart(member.getId(), ign, admin.getName());
        }
        logger.info("[whitelist] " + admin.getName() + " added " + ign + " (" + edition + (donor ? ", donor" : "")
                + ", discord=" + member.getId() + ")" + (problem == null ? "" : ": " + problem));
        String donorNote = donor ? giveDonorRole(hook.getJDA(), member) : "";
        audit(hook.getJDA(), Embeds.SUCCESS, Glyph.PLUS + " Member added", admin, member.getId(), ign,
                edition + (donor ? " · " + Glyph.GEM + " Donor" : ""));
        String welcome = sendWelcome(hook.getJDA(), member, new Added(0, ign, edition, donor, false));
        StringBuilder text = new StringBuilder("`" + ign + "` (" + edition + ") for " + member.getAsMention() + "\n")
                .append(problem == null ? Glyph.YES + " Whitelisted" : Glyph.WARN + " Whitelist: " + problem).append('\n')
                .append("They link their account by running /link in Minecraft.\n").append(welcome);
        if (!donorNote.isEmpty()) {
            text.append('\n').append(donorNote.strip());
        }
        hook.editOriginalEmbeds(Embeds.base(problem == null ? Embeds.SUCCESS : Embeds.WARNING)
                .setTitle(Glyph.YES + " Member added").setDescription(text).build()).queue();
        return true;
    }

    /** The strikes that led to a removal (the last three), or a general reason when they've expired. */
    String bannedReason(RemovedMember removed) {
        try {
            List<String> reasons = SyncBridge.await(tracking.strikesSince(removed.discordId(), 0), PlayerService.DB_TIMEOUT_MS).stream()
                    .filter(s -> s.givenAt() <= removed.removedAt()).map(TrackingRepository.Strike::reason)
                    .filter(s -> s != null && !s.isBlank()).toList();
            return reasons.isEmpty() ? "too many activity-check strikes (missing the weekly playtime)"
                    : String.join("; ", reasons.subList(Math.max(0, reasons.size() - 3), reasons.size()));
        } catch (Exception e) {
            return "too many activity-check strikes";
        }
    }

    /** A returning player: forget the old removal and take back strikes that still count. */
    private void freshStart(String discordId, String ign, String staff) {
        try {
            SyncBridge.await(tracking.clearRemovals(discordId, ign), PlayerService.DB_TIMEOUT_MS);
            long now = System.currentTimeMillis();
            for (TrackingRepository.Strike strike : SyncBridge.await(tracking.strikesSince(discordId, 0), PlayerService.DB_TIMEOUT_MS)) {
                SyncBridge.await(tracking.removeStrike(strike.id(), staff + " (added back)", now), PlayerService.DB_TIMEOUT_MS);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "[whitelist] couldn't clear the old removal for " + ign, e);
        }
    }

    /** Gives a new member the Donor role; returns a line saying how that went. */
    private String giveDonorRole(net.dv8tion.jda.api.JDA jda, User member) {
        String roleId = settings.whitelistDonorRoleId();
        if (roleId.isEmpty()) {
            return "";
        }
        var guild = jda.getGuildById(settings.guildId());
        var role = guild == null ? null : guild.getRoleById(roleId);
        if (role == null) {
            logger.warning("[whitelist] Donor role " + roleId + " not found (whitelist.donor-role-id)");
            return Glyph.WARN + " Couldn't find the Donor role (<@&" + roleId + ">), so it wasn't given.";
        }
        try {
            guild.addRoleToMember(net.dv8tion.jda.api.entities.UserSnowflake.fromId(member.getId()), role).complete();
            return Glyph.GEM + " Gave them the " + role.getAsMention() + " role.";
        } catch (RuntimeException e) {
            logger.warning("[whitelist] couldn't give the Donor role to " + member.getId() + ": " + e.getMessage());
            return Glyph.WARN + " Couldn't give the " + role.getAsMention() + " role (the bot needs Manage Roles, with its role above Donor).";
        }
    }

    /** Looks the member up and asks for confirmation before anything is deleted. */
    void remove(InteractionHook hook, User admin, User member, String ign) {
        if (member == null && (ign == null || ign.isBlank())) {
            hook.editOriginal("Give either `discord` or `ign` to say who to remove.").queue();
            return;
        }
        if (editor == null && roster != null) {
            Roster.Removed removed = roster.remove(member == null ? null : member.getId(), ign);
            if (removed.ign() == null && !removed.unlinked()) {
                hook.editOriginal(member.getAsMention() + " isn't linked, so there's no Minecraft name to remove. Give `ign` too.").queue();
                return;
            }
            logger.info("[whitelist] " + admin.getName() + " removed " + removed.ign());
            audit(hook.getJDA(), Embeds.DANGER, Glyph.MINUS + " Member removed", admin, member == null ? null : member.getId(),
                    removed.ign(), removed.unlinked() ? "unlinked and un-whitelisted" : "un-whitelisted");
            hook.editOriginalEmbeds(Embeds.base(Embeds.DANGER).setTitle(Glyph.DELETE + " Removed")
                    .setDescription("`" + removed.ign() + "`" + (removed.unlinked() ? " was unlinked and" : " was")
                            + " taken off the whitelist."
                            + (removed.problem() == null ? "" : "\n" + Glyph.WARN + " " + removed.problem()))
                    .build()).queue();
            return;
        }
        if (editor == null) {
            hook.editOriginal("Sheet sync is turned off, so there's no sheet to remove from.").queue();
            return;
        }
        Match match;
        try {
            match = editor.find(member == null ? null : member.getId(), ign);
        } catch (Exception e) {
            logger.log(Level.WARNING, "/whitelist remove lookup failed", e);
            hook.editOriginal("Couldn't read the sheet: " + e.getMessage()).queue();
            return;
        }
        if (match == null) {
            hook.editOriginal((member != null ? member.getAsMention() : "`" + ign + "`") + " isn't in the sheet.").queue();
            return;
        }
        String who = "`" + match.ign() + "`" + (match.discord().matches("\\d{17,20}") ? " (<@" + match.discord() + ">)" : "");
        hook.editOriginalEmbeds(Embeds.base(Embeds.DANGER)
                        .setTitle(Glyph.DELETE + " Remove " + match.ign() + "?")
                        .setDescription(who + " is on row " + match.row() + " of the sheet"
                                + (match.table() == 0 ? ", in the **original members** table" : "") + ".\n"
                                + "Removing deletes that row, takes them off the whitelist, unlinks them and removes "
                                + "the linked role.")
                        .build())
                .setComponents(ActionRow.of(
                        Button.danger(CONFIRM_PREFIX + admin.getId() + ":" + match.row() + ":" + match.ign(), "Remove"),
                        Button.secondary(CANCEL_PREFIX + admin.getId(), "Cancel")))
                .queue();
    }

    /** Handles the Remove / Cancel buttons; returns false for buttons that aren't ours. */
    boolean onButton(ButtonInteractionEvent event, java.util.concurrent.Executor pool) {
        String id = event.getComponentId();
        if (id.startsWith(CANCEL_PREFIX)) {
            event.editMessage("Cancelled; nothing was changed.").setEmbeds().setComponents().queue();
            return true;
        }
        if (id.startsWith(READD_PREFIX)) {
            String[] p = id.substring(READD_PREFIX.length()).split(":", 5);
            if (p.length != 5 || !p[0].equals(event.getUser().getId())) {
                event.reply("Only the staff member who ran this can confirm it.").setEphemeral(true).queue();
                return true;
            }
            event.deferEdit().queue(hook -> pool.execute(() -> {
                User member;
                try {
                    member = event.getJDA().retrieveUserById(p[1]).complete();
                } catch (RuntimeException e) {
                    hook.editOriginal("Couldn't find that Discord account any more.").setEmbeds().setComponents().queue();
                    return;
                }
                hook.editOriginalComponents().queue();
                add(hook, event.getUser(), member, p[2], p[4], p[3].equals("1"), true);
            }));
            return true;
        }
        if (!id.startsWith(CONFIRM_PREFIX)) {
            return false;
        }
        String[] parts = id.substring(CONFIRM_PREFIX.length()).split(":", 3);
        if (parts.length != 3 || !parts[0].equals(event.getUser().getId())) {
            event.reply("Only the admin who ran this can confirm it.").setEphemeral(true).queue();
            return true;
        }
        int row = Integer.parseInt(parts[1]);
        String ign = parts[2];
        event.deferEdit().queue(hook -> pool.execute(() -> {
            try {
                editor.remove(row, ign);
            } catch (EditException e) {
                hook.editOriginal(e.getMessage()).setEmbeds().setComponents().queue();
                return;
            } catch (Exception e) {
                logger.log(Level.WARNING, "/whitelist remove failed", e);
                hook.editOriginal("Couldn't remove them from the sheet: " + e.getMessage()).setEmbeds().setComponents().queue();
                return;
            }
            logger.info("[whitelist] " + event.getUser().getName() + " removed " + ign + " (row " + row + ") from the sheet");
            audit(event.getJDA(), Embeds.DANGER, Glyph.MINUS + " Member removed", event.getUser(), null, ign, "was on sheet row " + row);
            hook.editOriginalEmbeds(Embeds.base(Embeds.DANGER).setTitle(Glyph.DELETE + " Removed from the sheet")
                    .setDescription("`" + ign + "` was deleted from the sheet. Unlinking and un-whitelisting now…").build())
                    .setComponents().queue();

            syncThen(hook, "/whitelist remove by " + event.getUser().getName(), report -> {
                boolean unlinked = report.removal().removed().stream().anyMatch(link -> ign.equalsIgnoreCase(link.name()));
                boolean unwhitelisted = report.whitelist() != null
                        && report.whitelist().removed().stream().anyMatch(name -> name.equalsIgnoreCase(ign));
                StringBuilder text = new StringBuilder("`" + ign + "` was deleted from the sheet.\n");
                text.append(unlinked ? Glyph.YES + " Unlinked" : "Wasn't linked").append('\n');
                if (report.removal().skippedReason() != null) {
                    text.append(Glyph.WARN + " ").append(report.removal().skippedReason()).append('\n');
                }
                if (report.whitelist() == null) {
                    text.append("Whitelist sync is off.");
                } else if (report.whitelist().error() != null) {
                    text.append(Glyph.WARN + " Whitelist: ").append(report.whitelist().error());
                } else {
                    text.append(unwhitelisted ? Glyph.YES + " Removed from the whitelist"
                            : "Not removed from the whitelist (the plugin didn't add them, so an admin must)");
                }
                return Embeds.base(Embeds.SUCCESS).setTitle(Glyph.YES + " Member removed").setDescription(text).build();
            });
        }));
        return true;
    }

    /** Returns the line shown in the reply about the welcome DM. */
    private String sendWelcome(net.dv8tion.jda.api.JDA jda, User member, Added added) {
        if (settings.welcomeMessage().isBlank()) {
            return "Welcome DM is off.";
        }
        String message = settings.welcomeMessage()
                .replace("{player}", added.ign())
                .replace("{edition}", added.edition())
                .replace("{required}", Embeds.formatHours(Math.round(settings.activityMinHours() * 3600)));
        String failure = ActivityCheck.sendDm(jda, member.getId(), message);
        return failure == null ? Glyph.YES + " Welcome DM sent" : Glyph.WARN + " Welcome DM failed (" + failure + ")";
    }

    /** Staff audit trail: who changed the member list, and how. */
    private void audit(net.dv8tion.jda.api.JDA jda, int color, String title, User admin, String memberId, String ign,
                       String detail) {
        var channel = Channels.find(jda, settings.auditChannelId(), "notices.audit-channel-id", logger);
        if (channel == null) {
            return;
        }
        channel.sendMessageEmbeds(Embeds.base(color)
                        .setTitle(title)
                        .setDescription("`" + ign + "`" + (memberId == null ? "" : " · <@" + memberId + ">") + "\n" + detail)
                        .setFooter("By " + admin.getName() + " (" + admin.getId() + ")")
                        .setTimestamp(java.time.Instant.now())
                        .build())
                .setAllowedMentions(List.of())
                .queue(null, error -> logger.warning("[whitelist] could not post to the audit channel: " + error.getMessage()));
    }

    /** Runs a fresh sync and replaces the reply with {@code describe}'s embed, or the error. */
    private void syncThen(InteractionHook hook, String trigger,
                          java.util.function.Function<Report, net.dv8tion.jda.api.entities.MessageEmbed> describe) {
        importer.runFresh(trigger).orTimeout(2, TimeUnit.MINUTES).whenComplete((report, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                hook.editOriginal("The sheet was updated, but the sync that follows failed: " + cause.getMessage()
                        + ". It will be retried on the next scheduled sync.").setEmbeds().queue();
                return;
            }
            hook.editOriginalEmbeds(describe.apply(report)).setAllowedMentions(List.of()).queue();
        });
    }
}
