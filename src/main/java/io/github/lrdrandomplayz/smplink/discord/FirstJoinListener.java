package io.github.lrdrandomplayz.smplink.discord;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import io.github.lrdrandomplayz.smplink.db.LinkRepository;
import io.github.lrdrandomplayz.smplink.db.LinkRepository.Link;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Announces a player's first ever join on the Minecraft server. */
public final class FirstJoinListener implements Listener {

    private final DiscordBot bot;
    private final LinkRepository links;
    private final Logger logger;

    public FirstJoinListener(DiscordBot bot, LinkRepository links, Logger logger) {
        this.bot = bot;
        this.links = links;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (event.getPlayer().hasPlayedBefore()) {
            return;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();
        // On this hybrid server the link may still hold the Mojang UUID until the next sync swaps in the
        // one the player actually joined with, so fall back to matching by name.
        links.findByUuid(uuid)
                .thenCompose(found -> found.isPresent() ? CompletableFuture.completedFuture(found) : links.findByName(name))
                .thenAccept(link -> bot.welcome().firstJoin(bot.jda(), link.map(Link::discordId).orElse(null), name))
                .exceptionally(error -> {
                    logger.log(Level.WARNING, "Could not announce the first join of " + name, error);
                    return null;
                });
    }
}
