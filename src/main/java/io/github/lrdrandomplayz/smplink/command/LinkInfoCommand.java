package io.github.lrdrandomplayz.smplink.command;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.lrdrandomplayz.smplink.db.LinkRepository;
import io.github.lrdrandomplayz.smplink.db.LinkRepository.Link;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.TabExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class LinkInfoCommand implements TabExecutor {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final JavaPlugin plugin;
    private final LinkRepository links;

    public LinkInfoCommand(JavaPlugin plugin, LinkRepository links) {
        this.plugin = plugin;
        this.links = links;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            sender.sendMessage(Component.text("Usage: /linkinfo <player>", NamedTextColor.RED));
            return true;
        }
        String name = args[0];

        // The user cache knows players who joined under a different name than the one stored at import.
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        CompletableFuture<Optional<Link>> lookup = cached == null
                ? links.findByName(name)
                : links.findByUuid(cached.getUniqueId()).thenCompose(found ->
                        found.isPresent() ? CompletableFuture.completedFuture(found) : links.findByName(name));

        lookup.whenComplete((result, error) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> reply(sender, name, result, error));
        });
        return true;
    }

    private void reply(CommandSender sender, String name, Optional<Link> result, Throwable error) {
        if (error != null) {
            plugin.getLogger().log(Level.WARNING, "/linkinfo lookup for " + name + " failed", error);
            sender.sendMessage(Component.text("Lookup failed, check the console.", NamedTextColor.RED));
            return;
        }
        if (result.isEmpty()) {
            sender.sendMessage(Component.text(name + " is not linked to a Discord account.", NamedTextColor.YELLOW));
            return;
        }
        Link link = result.get();
        sender.sendMessage(Component.text()
                .append(Component.text((link.name() != null ? link.name() : name) + " → Discord ", NamedTextColor.GRAY))
                .append(Component.text(link.discordId(), NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.copyToClipboard(link.discordId()))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to copy"))))
                .append(Component.text(" (linked " + DATE.format(Instant.ofEpochMilli(link.linkedAt()))
                        + ", " + link.uuid() + ")", NamedTextColor.DARK_GRAY))
                .build());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase();
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(n -> n.toLowerCase().startsWith(prefix))
                .toList();
    }
}
