package io.github.lrdrandomplayz.smplink.discord;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.emoji.ApplicationEmoji;
import net.dv8tion.jda.api.entities.emoji.Emoji;

/**
 * The bot's own emoji set, drawn by {@link GlyphArt} and uploaded to the application (bot) emojis at startup, so
 * they work everywhere the bot posts. Until they're uploaded, or if uploading fails, each glyph falls back to its
 * normal emoji. Concatenating a glyph into text gives its mention; use {@link #emoji()} for buttons and menus.
 * Only use glyphs where Discord renders custom emojis: message text, embed titles, descriptions and fields, and
 * V2 text. Footers, author lines, button labels, modal text and Minecraft chat need {@link #unicode()}.
 */
enum Glyph {

    // Hand-made pixel icons: shape and colour.
    YES("✅", "check", 0x3FBF3F),
    NO("❌", "cross", 0xE03C31),
    WARN("⚠️", "warn", 0xF0B232),
    ALERT("❗", "alert", 0xE03C31),
    QUESTION("❔", "question", 0x7AA7FF),
    PLUS("➕", "plus", 0x3FBF3F),
    MINUS("➖", "minus", 0xE03C31),
    ARROW("➡️", "arrow", 0xB7BCC4),
    UP("🔼", "up", 0x3FBF3F),
    DOWN("🔽", "down", 0xE03C31),
    ONLINE("🟢", "dot", 0x3FBF3F),
    OFFLINE("⚫", "dot", 0x6B6F76),
    RED_DOT("🔴", "dot", 0xE03C31),

    // Minecraft items.
    SHIELD("🛡️", "shield"),
    TIME("⏱️", "clock"),
    ACTIVITY("📉", "cobweb"),
    BANNED("⛔", "barrier"),
    CALENDAR("📅", "map"),
    MONEY("💰", "gold_block"),
    COINS("🪙", "gold_nugget"),
    TRADE("🤝", "emerald"),
    SHOP("🛒", "emerald_block"),
    PACKAGE("📦", "barrel"),
    GIFT("🎁", "chest"),
    LOCKED("🔒", "iron_bars"),
    KILLS("⚔️", "diamond_sword"),
    ASSASSIN("🗡️", "iron_sword"),
    TNT("💣", "tnt"),
    RAID("🏴‍☠️", "wither_skeleton_skull"),
    DEATHS("💀", "skeleton_skull"),
    EVENT("⚡", "lightning_rod"),
    BELL("🙋", "bell"),
    SECOND("🥈", "iron_ingot"),
    THIRD("🥉", "copper_ingot"),
    EDIT("✏️", "writable_book"),
    NOTE("📋", "paper"),
    BOOK("📜", "book"),
    STATS("📊", "knowledge_book"),
    PLAYER("🎮", "player_head"),
    MEDIA("🎬", "painting"),
    CHAT("💬", "oak_sign"),
    DELETE("🗑️", "lava_bucket"),
    SEARCH("🔎", "spyglass"),
    MINING("⛏️", "diamond_pickaxe"),
    STRIKE("🔨", "anvil"),
    CRAFT("🛠️", "crafting_table"),
    TARGET("🎯", "target"),
    RESTART("🔄", "respawn_anchor"),
    WELCOME("👋", "cake"),
    SLOTS("🎰", "dispenser"),
    WORLD("🌍", "grass_block"),
    DISTANCE("👣", "leather_boots"),
    WATCH("👀", "ender_eye"),
    INVENTORY("🎒", "bundle"),
    NEW("🆕", "oak_sapling"),
    BRAND("🟪", "amethyst_shard"),
    XP("📈", "experience_bottle"),
    LOCATION("📍", "compass"),
    UNDO("↩️", "recovery_compass"),
    REPORT("🚩", "red_banner"),
    STREAK("🔥", "fire_charge"),
    PAYMENT("💳", "gold_ingot"),

    // Minecraft items with the enchanted shimmer.
    PARTY("🎉", "firework_rocket", true),
    TROPHY("🏆", "totem_of_undying", true),
    GEM("💎", "diamond", true),
    CROWN("👑", "golden_helmet", true),
    BOOST("🚀", "amethyst_shard", true),
    SPARKLE("✨", "nether_star", true),
    FIRST("🥇", "gold_ingot", true),
    MILESTONE("🎖️", "beacon", true),
    DRAGON("🐉", "dragon_head");

    private static final String PREFIX = "dl_";
    private static final Map<Glyph, String> MENTIONS = new EnumMap<>(Glyph.class);

    private final String unicode;
    private final String item;
    private final String shape;
    private final int colour;
    private final boolean animated;

    Glyph(String unicode, String shape, int colour) {
        this.unicode = unicode;
        this.item = null;
        this.shape = shape;
        this.colour = colour;
        this.animated = false;
    }

    Glyph(String unicode, String item) {
        this(unicode, item, false);
    }

    Glyph(String unicode, String item, boolean animated) {
        this.unicode = unicode;
        this.item = "minecraft:" + item;
        this.shape = null;
        this.colour = 0;
        this.animated = animated;
    }

    String unicode() {
        return unicode;
    }

    String item() {
        return item;
    }

    String shape() {
        return shape;
    }

    int colour() {
        return colour;
    }

    boolean animated() {
        return animated;
    }

    /** The uploaded emoji's mention, or the normal emoji until it's uploaded. */
    @Override
    public String toString() {
        synchronized (MENTIONS) {
            return MENTIONS.getOrDefault(this, unicode);
        }
    }

    /** For buttons and select options. */
    Emoji emoji() {
        return Emoji.fromFormatted(toString());
    }

    /**
     * The emoji's name: "dl_yes_3fa2". The suffix comes from the picture, so a redrawn glyph is uploaded
     * again under a new name (and the old one deleted) instead of keeping the old picture.
     */
    String emojiName(byte[] picture) {
        String base = PREFIX + name().toLowerCase(Locale.ROOT);
        return base + "_" + String.format("%04x", java.util.Arrays.hashCode(picture) & 0xFFFF);
    }

    /**
     * Uploads missing glyphs as application emojis and removes outdated ones. Blocking; run it off the
     * JDA threads. Emoji uploads are rate limited, so the first start takes a minute or two.
     */
    static void install(JDA jda, Logger logger) {
        List<ApplicationEmoji> existing;
        try {
            existing = jda.retrieveApplicationEmojis().complete();
        } catch (RuntimeException e) {
            logger.warning("[icons] Could not read the bot's emojis, so normal emojis are used: " + e.getMessage());
            return;
        }
        Map<String, ApplicationEmoji> byName = new HashMap<>();
        existing.forEach(e -> byName.put(e.getName(), e));
        List<String> wanted = new ArrayList<>();
        List<String> noIcon = new ArrayList<>();
        int uploaded = 0;
        for (Glyph glyph : values()) {
            try {
                byte[] picture = GlyphArt.draw(glyph);
                String name = glyph.emojiName(picture);
                wanted.add(name);
                ApplicationEmoji emoji = byName.get(name);
                if (emoji == null) {
                    emoji = jda.createApplicationEmoji(name,
                            Icon.from(picture, glyph.animated ? Icon.IconType.GIF : Icon.IconType.PNG)).complete();
                    uploaded++;
                }
                synchronized (MENTIONS) {
                    MENTIONS.put(glyph, emoji.getFormatted());
                }
            } catch (java.io.FileNotFoundException e) {
                noIcon.add(e.getMessage());
            } catch (Exception e) {
                logger.warning("[icons] " + glyph.name() + " stays a normal emoji: " + e.getMessage());
            }
        }
        int removed = 0;
        for (ApplicationEmoji emoji : existing) {
            if (emoji.getName().startsWith(PREFIX) && !wanted.contains(emoji.getName())) {
                try {
                    emoji.delete().complete();
                    removed++;
                } catch (RuntimeException e) {
                    logger.warning("[icons] Could not delete the old emoji " + emoji.getName() + ": " + e.getMessage());
                }
            }
        }
        logger.info("[icons] " + MENTIONS.size() + " of " + values().length + " icons ready"
                + (uploaded > 0 ? " (" + uploaded + " uploaded)" : "") + (removed > 0 ? ", " + removed + " old ones removed" : ""));
        if (!noIcon.isEmpty()) {
            logger.info("[icons] " + noIcon.size() + " use normal emojis until these are in plugins/SMPLink/icons: "
                    + String.join(", ", noIcon));
        }
    }
}
