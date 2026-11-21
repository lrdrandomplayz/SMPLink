package io.github.lrdrandomplayz.smplink.sync;

import io.github.lrdrandomplayz.smplink.PluginSettings;
import io.github.lrdrandomplayz.smplink.db.LinkRepository;
import io.github.lrdrandomplayz.smplink.discord.Roster;
import io.github.lrdrandomplayz.smplink.http.SyncBridge;
import io.github.lrdrandomplayz.smplink.service.PlayerService;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.Bukkit;

/** {@link Roster} for linking.mode: codes, using the whitelist commands from config.yml and the links table. */
public final class ServerRoster implements Roster {

    private final PluginSettings settings;
    private final LinkRepository links;
    private final SyncBridge sync;
    private final Logger logger;

    public ServerRoster(PluginSettings settings, LinkRepository links, SyncBridge sync, Logger logger) {
        this.settings = settings;
        this.links = links;
        this.sync = sync;
        this.logger = logger;
    }

    @Override
    public String whitelist(String ign) {
        if (!settings.whitelistEnabled()) {
            return "whitelist.enabled is off, so they weren't whitelisted";
        }
        return run(settings.whitelistAddCommand(), ign);
    }

    @Override
    public Removed remove(String discordId, String ign) {
        try {
            Optional<LinkRepository.Link> link = discordId != null
                    ? SyncBridge.await(links.findByDiscord(discordId), PlayerService.DB_TIMEOUT_MS)
                    : ign == null ? Optional.empty() : SyncBridge.await(links.findByName(ign), PlayerService.DB_TIMEOUT_MS);
            String name = link.map(LinkRepository.Link::name).orElse(ign);
            boolean unlinked = false;
            if (link.isPresent()) {
                unlinked = SyncBridge.await(links.removeByDiscord(link.get().discordId()), PlayerService.DB_TIMEOUT_MS).isPresent();
            }
            String problem = null;
            if (name != null && settings.whitelistEnabled()) {
                problem = run(settings.whitelistRemoveCommand(), name);
            }
            return new Removed(name, unlinked, problem);
        } catch (Exception e) {
            return new Removed(ign, false, "couldn't update the link (" + e.getMessage() + ")");
        }
    }

    private String run(String template, String ign) {
        if (template.isBlank()) {
            return null;
        }
        if (!ign.matches("[A-Za-z0-9_.]{1,32}")) {
            return "\"" + ign + "\" isn't a Minecraft name";
        }
        String command = template.replace("{name}", ign).replaceFirst("^/", "");
        try {
            boolean ran = sync.call(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
            logger.info("[whitelist] ran \"" + command + "\"");
            return ran ? null : "the server didn't know the command \"" + command + "\"";
        } catch (Exception e) {
            return "\"" + command + "\" failed (" + e.getMessage() + ")";
        }
    }
}
