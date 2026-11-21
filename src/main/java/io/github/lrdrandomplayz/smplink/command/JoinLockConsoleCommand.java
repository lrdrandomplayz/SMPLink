package io.github.lrdrandomplayz.smplink.command;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.lrdrandomplayz.smplink.guard.JoinLock;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.Plugin;

/** /joinlock until 2026-11-07 13:00 [message] | off | status, for the console and operators (the same as Discord's). */
public final class JoinLockConsoleCommand implements TabExecutor {

    private static final String USAGE = "Usage: /joinlock until <yyyy-MM-dd> <HH:mm> [message] | off | status";

    private final Plugin plugin;
    private final JoinLock lock;

    public JoinLockConsoleCommand(Plugin plugin, JoinLock lock) {
        this.plugin = plugin;
        this.lock = lock;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "until" -> {
                if (args.length < 3) {
                    sender.sendMessage(Component.text(USAGE, NamedTextColor.RED));
                    return true;
                }
                long until;
                try {
                    until = lock.parse(args[1] + " " + args[2]);
                } catch (IllegalArgumentException e) {
                    sender.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
                    return true;
                }
                if (until <= System.currentTimeMillis()) {
                    sender.sendMessage(Component.text(lock.shown(until) + " has already passed.", NamedTextColor.RED));
                    return true;
                }
                String note = args.length > 3 ? String.join(" ", List.of(args).subList(3, args.length)) : "";
                // The lock saves to the database, so it's done off the main thread.
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    lock.lock(until, note, sender.getName());
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Component.text(
                            "Joining is locked until " + lock.shown(until) + ", then it opens by itself. Only operators, "
                                    + "admins and the server owner can join.", NamedTextColor.YELLOW)));
                });
            }
            case "off" -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                lock.unlock(sender.getName());
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Component.text("Joining is open again.", NamedTextColor.GREEN)));
            });
            case "status" -> sender.sendMessage(Component.text(lock.locked()
                    ? "Joining is locked until " + lock.shown(lock.until()) + "."
                    : "Joining is open.", NamedTextColor.YELLOW));
            default -> sender.sendMessage(Component.text(USAGE, NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("until", "off", "status").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList() : List.of();
    }
}
