package io.github.lrdrandomplayz.smplink.link;

import io.github.lrdrandomplayz.smplink.db.LinkRepository;
import java.util.List;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** In-game /link: gives the player a code to enter on Discord. */
public final class LinkGameCommand implements TabExecutor {

    private final Plugin plugin;
    private final LinkCodes codes;
    private final LinkRepository links;
    private final Logger logger;

    public LinkGameCommand(Plugin plugin, LinkCodes codes, LinkRepository links, Logger logger) {
        this.plugin = plugin;
        this.codes = codes;
        this.links = links;
        this.logger = logger;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can link an account.");
            return true;
        }
        links.findByUuid(player.getUniqueId()).whenComplete((existing, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (error != null) {
                logger.warning("[link] couldn't check " + player.getName() + "'s link: " + error.getMessage());
                player.sendMessage(Component.text("Couldn't check your link right now. Try again in a moment.", NamedTextColor.RED));
                return;
            }
            if (existing.isPresent()) {
                player.sendMessage(Component.text("You're already linked to a Discord account. Use /unlink on Discord first to change it.",
                        NamedTextColor.YELLOW));
                return;
            }
            String code = codes.create(player.getUniqueId(), player.getName());
            long minutes = Math.max(1, codes.lifetimeMs() / 60_000);
            player.sendMessage(Component.text("Your link code: ", NamedTextColor.GRAY)
                    .append(Component.text(code, NamedTextColor.GOLD, TextDecoration.BOLD)
                            .clickEvent(ClickEvent.copyToClipboard("/link code:" + code))
                            .hoverEvent(HoverEvent.showText(Component.text("Click to copy")))));
            player.sendMessage(Component.text("On Discord, run ", NamedTextColor.GRAY)
                    .append(Component.text("/link code:" + code, NamedTextColor.WHITE))
                    .append(Component.text(" within " + minutes + " minute" + (minutes == 1 ? "" : "s") + ".", NamedTextColor.GRAY)));
        }));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
