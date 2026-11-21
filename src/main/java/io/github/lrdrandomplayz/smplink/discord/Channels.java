package io.github.lrdrandomplayz.smplink.discord;

import java.util.logging.Logger;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;

final class Channels {

    private Channels() {
    }

    /** The channel with that ID, or null (logged, naming the config key) when it's unset or the bot can't see it. */
    static GuildMessageChannel find(JDA jda, String id, String configKey, Logger logger) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        GuildMessageChannel channel = jda.getChannelById(GuildMessageChannel.class, id);
        if (channel == null) {
            logger.warning("Channel " + id + " (" + configKey + ") was not found or the bot can't see it.");
        }
        return channel;
    }
}
