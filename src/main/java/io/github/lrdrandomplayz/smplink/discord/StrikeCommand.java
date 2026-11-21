package io.github.lrdrandomplayz.smplink.discord;

import java.util.List;
import java.util.Optional;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import io.github.lrdrandomplayz.smplink.PluginSettings;
import io.github.lrdrandomplayz.smplink.db.LinkRepository.Link;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.Strike;
import io.github.lrdrandomplayz.smplink.service.PlayerService;

/** /strike add and /strike remove: strikes given and taken back by hand (admin only). */
final class StrikeCommand {

    private final PlayerService players;
    private final StrikeService strikeService;
    private final PluginSettings settings;

    StrikeCommand(PlayerService players, StrikeService strikeService, PluginSettings settings) {
        this.players = players;
        this.strikeService = strikeService;
        this.settings = settings;
    }

    static SlashCommandData definition() {
        return Commands.slash("strike", "Give or take back a strike (admin only)").addSubcommands(
                new SubcommandData("add", "Give a linked member a strike")
                        .addOptions(new OptionData(OptionType.USER, "user", "Who gets the strike", true),
                                new OptionData(OptionType.STRING, "reason", "Why (the member sees this)", true).setRequiredLength(3, 200)),
                new SubcommandData("remove", "Take back one of a member's strikes by its ID")
                        .addOptions(new OptionData(OptionType.USER, "user", "Whose strike it is", true),
                                new OptionData(OptionType.INTEGER, "id", "The strike's ID, e.g. 12 (shown as #12)", true).setMinValue(1)));
    }

    /** Runs on a worker thread after an ephemeral defer. The member must be linked, so the strike has a player. */
    void add(InteractionHook hook, User admin, User member, String reason) throws Exception {
        Link link = players.link(member.getId());
        StrikeService.Given given = strikeService.give(hook.getJDA(), new StrikeService.Target(link.discordId(), link.uuid(), link.name()),
                "manual", reason.trim(), admin.getName(), null, System.currentTimeMillis(),
                settings.strikeMessage(), settings.strikeRemovedMessage(), List.of());
        hook.editOriginal(Glyph.ALERT + " Strike **#" + given.id() + "** given to <@" + link.discordId() + "> (`" + link.name() + "`): " + reason.trim()
                        + "\nThey now have **" + given.active().size() + " of " + given.limit() + "** strikes."
                        + (given.removed() ? "\n" + Glyph.BANNED + " That reached their limit, so they were **permanently removed**." : "")
                        + (given.problem() == null ? "" : "\n" + Glyph.WARN + " **Needs an admin:** " + given.problem())
                        + (given.dmFailure() == null ? "" : "\n" + Glyph.WARN + " Their DM failed (" + given.dmFailure() + ")."))
                .setAllowedMentions(List.of()).queue();
    }

    /** The member is asked for as well as the ID, so a mistyped ID can't take back someone else's strike. */
    void remove(InteractionHook hook, JDA jda, User admin, User member, long id) throws Exception {
        Optional<Strike> found = strikeService.find(id);
        if (found.isPresent() && !found.get().discordId().equals(member.getId())) {
            hook.editOriginal("Strike **#" + id + "** isn't <@" + member.getId() + ">'s, so nothing was removed. "
                            + activeList(member.getId()))
                    .setAllowedMentions(List.of()).queue();
            return;
        }
        Optional<StrikeService.Taken> taken = strikeService.remove(jda, id, admin.getName());
        if (taken.isEmpty()) {
            hook.editOriginal("<@" + member.getId() + "> has no active strike **#" + id + "**"
                            + (found.isPresent() ? " (it was already removed). " : ". ") + activeList(member.getId()))
                    .setAllowedMentions(List.of()).queue();
            return;
        }
        StrikeService.Taken result = taken.get();
        hook.editOriginal(Glyph.UNDO + " Strike **#" + id + "** removed from <@" + result.strike().discordId() + "> (`" + result.strike().ign()
                        + "`): " + result.strike().reason() + "\nThey now have **" + result.active().size() + " of " + result.limit() + "** strikes."
                        + (result.removal().isPresent() ? "\n" + Glyph.WARN + " They were already permanently removed. This doesn't re-add them; add "
                        + "them to the sheet by hand if they should come back." : "")
                        + (result.dmFailure() == null ? "" : "\n" + Glyph.WARN + " Their DM failed (" + result.dmFailure() + ")."))
                .setAllowedMentions(List.of()).queue();
    }

    /** "Their active strikes: `#4` 📉 …, `#12` 🏴‍☠️ …" so the admin can pick the right ID. */
    private String activeList(String discordId) throws Exception {
        List<Strike> active = strikeService.active(discordId);
        if (active.isEmpty()) {
            return "They have no active strikes.";
        }
        StringBuilder text = new StringBuilder("Their active strikes:");
        for (Strike strike : active) {
            text.append("\n• ").append(Strikes.describe(strike));
        }
        return text.toString();
    }
}
