package io.github.lrdrandomplayz.smplink.discord;

import java.util.List;
import java.util.logging.Logger;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import io.github.lrdrandomplayz.smplink.PluginSettings;
import io.github.lrdrandomplayz.smplink.db.LinkRepository.Change;

/** Public welcome posts: one when a member is first linked, one when they first join the Minecraft server. */
final class WelcomePoster {

    // A sync that links this many players at once is a fresh install or a restored database, not new members.
    private static final int MAX_WELCOMES_PER_SYNC = 10;

    private final PluginSettings settings;
    private final Logger logger;

    WelcomePoster(PluginSettings settings, Logger logger) {
        this.settings = settings;
        this.logger = logger;
    }

    /** Called after each sync with the links it created. */
    void welcomeNewLinks(JDA jda, List<Change> changes) {
        List<Change> linked = changes.stream().filter(change -> change.action().equals("linked")).toList();
        if (linked.isEmpty() || jda == null) {
            return;
        }
        if (linked.size() > MAX_WELCOMES_PER_SYNC) {
            logger.info("[welcome] " + linked.size() + " players were linked at once; skipping welcome posts");
            return;
        }
        GuildMessageChannel channel = Channels.find(jda, settings.welcomeChannelId(), "welcome.channel-id", logger);
        if (channel == null) {
            return;
        }
        for (Change change : linked) {
            channel.sendMessageEmbeds(Embeds.base(Embeds.SUCCESS)
                            .setTitle(Glyph.WELCOME + " Welcome to " + io.github.lrdrandomplayz.smplink.Branding.name() + "!")
                            .setDescription("Please welcome <@" + change.discordId() + ">!\n"
                                    + Glyph.PLAYER + " Minecraft: `" + change.name() + "`\n\nSay hi and see you in game!")
                            .setThumbnail(Embeds.skin("body", change.name()))
                            .build())
                    .setAllowedMentions(List.of())
                    .queue(null, error -> logger.warning("[welcome] could not welcome " + change.name() + ": " + error.getMessage()));
        }
    }

    /** {@code discordId} is null when the player isn't linked (yet). */
    void firstJoin(JDA jda, String discordId, String ign) {
        if (jda == null) {
            return;
        }
        GuildMessageChannel channel = Channels.find(jda, settings.firstJoinChannelId(), "welcome.first-join-channel-id", logger);
        if (channel == null) {
            return;
        }
        String who = discordId == null ? "`" + ign + "`" : "<@" + discordId + "> (`" + ign + "`)";
        channel.sendMessageEmbeds(Embeds.base(Embeds.BRAND)
                        .setTitle(Glyph.PARTY + " First time on the server!")
                        .setDescription(who + " just joined " + io.github.lrdrandomplayz.smplink.Branding.name() + " for the first time.")
                        .setThumbnail(Embeds.skin("avatar", ign))
                        .build())
                .setAllowedMentions(List.of())
                .queue(null, error -> logger.warning("[welcome] could not post the first join of " + ign + ": " + error.getMessage()));
        logger.info("[welcome] " + ign + " joined for the first time");
    }
}
