package io.github.lrdrandomplayz.smplink.discord;

import io.github.lrdrandomplayz.smplink.db.LinkRepository;
import io.github.lrdrandomplayz.smplink.http.SyncBridge;
import io.github.lrdrandomplayz.smplink.link.LinkCodes;
import io.github.lrdrandomplayz.smplink.service.PlayerService;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

/** Discord /link (enter the code from Minecraft) and /unlink. */
final class LinkCommand {

    private final LinkCodes codes;
    private final LinkRepository links;
    private final Consumer<List<LinkRepository.Change>> onLinked;
    private final Consumer<LinkRepository.Link> onUnlinked;
    private final Logger logger;

    LinkCommand(LinkCodes codes, LinkRepository links, Consumer<List<LinkRepository.Change>> onLinked,
                Consumer<LinkRepository.Link> onUnlinked, Logger logger) {
        this.codes = codes;
        this.links = links;
        this.onLinked = onLinked;
        this.onUnlinked = onUnlinked;
        this.logger = logger;
    }

    static CommandData definition() {
        return Commands.slash("link", "Link your Discord account to your Minecraft account")
                .addOption(OptionType.STRING, "code", "The code from running /link in Minecraft", true);
    }

    static CommandData unlinkDefinition() {
        return Commands.slash("unlink", "Unlink your Discord account from your Minecraft account");
    }

    void onSlash(SlashCommandInteractionEvent event, ExecutorService pool) {
        String user = event.getUser().getId();
        String code = event.getOption("code", OptionMapping::getAsString);
        boolean unlink = event.getName().equals("unlink");
        event.deferReply(true).queue(hook -> pool.execute(() -> {
            try {
                hook.editOriginal(unlink ? unlink(user) : link(user, code)).queue();
            } catch (Exception e) {
                logger.log(Level.WARNING, "/" + event.getName() + " by " + user + " failed", e);
                hook.editOriginal("Something went wrong. The error has been logged in the server console.").queue();
            }
        }));
    }

    private String link(String discordId, String code) throws Exception {
        Optional<LinkRepository.Link> already = SyncBridge.await(links.findByDiscord(discordId), PlayerService.DB_TIMEOUT_MS);
        if (already.isPresent()) {
            return "You're already linked to **" + already.get().name() + "**. Use /unlink first to change it.";
        }
        Optional<LinkCodes.Pending> pending = codes.redeem(code);
        if (pending.isEmpty()) {
            return "That code is wrong or has expired. Run /link in Minecraft to get a new one.";
        }
        LinkCodes.Pending player = pending.get();
        LinkRepository.ImportResult result = SyncBridge.await(links.importEntries(
                List.of(new LinkRepository.ImportEntry(discordId, player.uuid(), player.name(), false)), false, "link"), PlayerService.DB_TIMEOUT_MS);
        if (!result.conflicts().isEmpty()) {
            return "**" + player.name() + "** is already linked to another Discord account. Ask staff if that's wrong.";
        }
        onLinked.accept(result.changes());
        return Glyph.YES + " Linked to **" + player.name() + "**.";
    }

    private String unlink(String discordId) throws Exception {
        Optional<LinkRepository.Link> removed = SyncBridge.await(links.removeByDiscord(discordId), PlayerService.DB_TIMEOUT_MS);
        if (removed.isEmpty()) {
            return "Your Discord account isn't linked.";
        }
        onUnlinked.accept(removed.get());
        return "Unlinked from **" + removed.get().name() + "**.";
    }
}
