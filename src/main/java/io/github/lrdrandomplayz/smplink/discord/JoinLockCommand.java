package io.github.lrdrandomplayz.smplink.discord;

import java.util.List;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import io.github.lrdrandomplayz.smplink.guard.JoinLock;

/**
 * /joinlock until <when> [message] | off | status: keeps everyone but staff out of the server until an opening
 * time, which opens by itself. admin only.
 */
final class JoinLockCommand {

    private final JoinLock lock;

    JoinLockCommand(JoinLock lock) {
        this.lock = lock;
    }

    static SlashCommandData definition() {
        return Commands.slash("joinlock", "Keep everyone but staff out until an opening time (admin only)").addSubcommands(
                new SubcommandData("until", "Lock joining until a date and time; it opens by itself then")
                        .addOption(OptionType.STRING, "when", "e.g. 2026-11-07 13:00 (24-hour clock, server time)", true)
                        .addOption(OptionType.STRING, "message", "An extra line players see when they're turned away", false),
                new SubcommandData("off", "Open joining now"),
                new SubcommandData("status", "Show whether joining is locked"));
    }

    /** Runs on a worker thread after an ephemeral defer. */
    void handle(InteractionHook hook, User admin, String sub, String when, String message) {
        switch (sub == null ? "status" : sub) {
            case "until" -> {
                long until;
                try {
                    until = lock.parse(when);
                } catch (IllegalArgumentException e) {
                    hook.editOriginal(Glyph.WARN + " " + e.getMessage()).queue();
                    return;
                }
                if (until <= System.currentTimeMillis()) {
                    hook.editOriginal(Glyph.WARN + " " + lock.shown(until) + " has already passed.").queue();
                    return;
                }
                lock.lock(until, message, admin.getName() + " (Discord)");
                hook.editOriginal("🔒 Joining is locked until **" + lock.shown(until) + "**, then it opens by itself.\n"
                        + "Operators, admins and the server owner can still join; everyone else online was sent out. "
                        + "Players see:\n" + quote(lock.message(System.currentTimeMillis()))).setAllowedMentions(List.of()).queue();
            }
            case "off" -> {
                boolean was = lock.locked();
                lock.unlock(admin.getName() + " (Discord)");
                hook.editOriginal(was ? "🔓 Joining is open again." : "🔓 Joining wasn't locked; it's open.").queue();
            }
            default -> hook.editOriginal(lock.locked()
                    ? "🔒 Joining is locked until **" + lock.shown(lock.until()) + "**. Players see:\n"
                            + quote(lock.message(System.currentTimeMillis()))
                    : "🔓 Joining is open. Lock it with `/joinlock until 2026-11-07 13:00`.").setAllowedMentions(List.of()).queue();
        }
    }

    private static String quote(String text) {
        return text.lines().map(line -> "> " + line).reduce((a, b) -> a + "\n" + b).orElse("");
    }
}
