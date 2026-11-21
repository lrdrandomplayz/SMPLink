package io.github.lrdrandomplayz.smplink.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.MessageEmbed;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.Strike;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.Warning;
import io.github.lrdrandomplayz.smplink.db.TrackingRepository.WarningRecord;
import io.github.lrdrandomplayz.smplink.service.PlayerService.Standing;
import org.junit.jupiter.api.Test;

class ProfileTest {

    private static final long NOW = System.currentTimeMillis();
    private static final long DAY = 24L * 60 * 60 * 1000;

    private static Standing member(boolean original) {
        return new Standing(3, 54, "100000000000000099", UUID.randomUUID(), "Steve", false,
                36_000, 3_600, 20_000, NOW - 40 * DAY, original, NOW - 60 * DAY, NOW - DAY, null);
    }

    @Test
    void menuOffersEveryPageAndMarksTheCurrentOne() {
        StringSelectMenu menu = (StringSelectMenu) ProfileCommand.menu("111", "222", ProfileCommand.Page.STRIKES).getComponents().get(0);

        assertEquals("profile:111:222", menu.getCustomId());
        assertEquals(List.of("stats", "playtime", "strikes"), menu.getOptions().stream().map(o -> o.getValue()).toList());
        assertTrue(menu.getOptions().get(2).isDefault());
        assertEquals(ProfileCommand.Page.PLAYTIME, ProfileCommand.Page.fromId("playtime"));
        assertEquals(ProfileCommand.Page.STATS, ProfileCommand.Page.fromId("nonsense"));
    }

    @Test
    void strikesPageListsActiveStrikesWithExpiry() {
        long expire = 90 * DAY;
        List<Strike> active = List.of(
                new Strike(4, "100000000000000099", "Steve", "activity", "Missed the activity-check deadline", "the activity check", NOW - 20 * DAY, null),
                new Strike(12, "100000000000000099", "Steve", "raid", "Offline raiding Steve's base at 120, -40", "Alex", NOW - 5 * DAY, null));
        Warning open = new Warning(9, "100000000000000099", UUID.randomUUID(), "Steve", NOW - 8 * DAY, NOW - DAY, NOW + 2 * DAY);
        List<WarningRecord> history = List.of(new WarningRecord("pending", NOW - DAY, NOW + 2 * DAY, 0),
                new WarningRecord("struck", NOW - 9 * DAY, NOW - 5 * DAY, NOW - 5 * DAY));

        List<ContainerChildComponent> parts = ProfileCommand.strikesBody("Alex", "Steve", active, 1, open, history, 3,
                expire, "3 months", null);
        String text = text(parts);

        assertTrue(text.contains("**2 of 3** strikes"), text);
        assertEquals(Embeds.DANGER, ProfileCommand.strikesColor(active.size()), "two strikes is red");
        assertTrue(text.contains("expires <t:" + (NOW - 5 * DAY + expire) / 1000 + ":R>"), text);
        // Each strike shows its ID and reason, so an admin can take it back with /strike remove.
        assertTrue(text.contains("`#12` 🏴‍☠️ Offline raiding Steve's base at 120, -40"), text);
        assertTrue(text.contains("`#4` 📉 Missed the activity-check deadline"), text);
        assertTrue(text.contains("### ⚠️ Open activity warning"), text);
        assertTrue(text.contains("-# Strikes last 3 months"), text);
        // Sections are split by dividers.
        assertTrue(parts.stream().filter(p -> p instanceof Separator).count() >= 3);
    }

    @Test
    void exemptMembersStillShowTheirStrikeLimit() {
        String text = text(ProfileCommand.strikesBody("Alex", "Steve", List.of(), 0, null, List.of(), 6, 90 * DAY,
                "3 months", "Original member"));
        // Original members and Donors aren't activity-checked, but raid strikes still apply, with their limit of 6.
        assertTrue(text.contains("**0 of 6** strikes"), text);
        assertTrue(text.contains("Original member: not part of the weekly activity check"), text);
        assertEquals(ProfileCommand.Page.STRIKES.color, ProfileCommand.strikesColor(0));

        MessageEmbed quota = Embeds.quota("Alex", member(false), "ignored", "3 h", "Donor");
        assertTrue(quota.getDescription().contains("**Donor**: not part of the weekly activity check"), quota.getDescription());
    }

    @Test
    void eachPageHasItsBannersColour() {
        assertEquals(0xBD1716, ProfileCommand.Page.STATS.color);
        assertEquals(0x1D2D43, ProfileCommand.Page.PLAYTIME.color);
        assertEquals(0xA96743, ProfileCommand.Page.STRIKES.color);
    }

    @Test
    void strikesColourFollowsTheCount() {
        assertEquals(0xA96743, ProfileCommand.strikesColor(0));
        assertEquals(Embeds.WARNING, ProfileCommand.strikesColor(1));
        assertEquals(Embeds.DANGER, ProfileCommand.strikesColor(2));
        assertEquals(Embeds.DANGER, ProfileCommand.strikesColor(3));
    }

    /** Every piece of text in a page, in order. */
    static String text(List<ContainerChildComponent> parts) {
        StringBuilder text = new StringBuilder();
        for (var part : parts) {
            if (part instanceof TextDisplay display) {
                text.append(display.getContent()).append('\n');
            } else if (part instanceof Section section) {
                section.getContentComponents().forEach(c -> text.append(((TextDisplay) c).getContent()).append('\n'));
            }
        }
        return text.toString();
    }

    @Test
    void statsPageGroupsTheNumbersWithDividers() {
        var stats = new io.github.lrdrandomplayz.smplink.service.PlayerService.Stats(UUID.randomUUID(), "Steve", false, null, 80.9, 12,
                1234, 5, 45_678, 89.4, NOW - 60 * DAY, NOW - DAY);
        List<ContainerChildComponent> parts = ProfileCommand.statsBody("Alex", stats,
                new io.github.lrdrandomplayz.smplink.db.TradeRepository.Rep(4, 1, 5), new io.github.lrdrandomplayz.smplink.db.TradeRepository.Rep(2, 0, 3));
        String text = text(parts);
        assertTrue(text.contains("### ⚔️ Combat\n⚔️ **1,234** mob kills"), text);
        assertTrue(text.contains("⛏️ **45,678** blocks mined"), text);
        assertTrue(text.contains("**+3** rep · 👍 4 · 🚩 1 · 5 trades"), text);
        assertTrue(text.contains("🛡️ Middleman **+2** · 3 trades"), text);
        assertTrue(parts.stream().filter(p -> p instanceof Separator).count() >= 3);
    }

    @Test
    void bannersAreShrunkToJpeg() throws Exception {
        // A photo-like 800x300 banner: smooth colour with noise, which PNG compresses badly and JPEG well.
        java.awt.image.BufferedImage banner = new java.awt.image.BufferedImage(800, 300, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(7);
        for (int y = 0; y < 300; y++) {
            for (int x = 0; x < 800; x++) {
                int n = random.nextInt(24);
                banner.setRGB(x, y, ((x * 255 / 800 + n) & 0xFF) << 16 | ((y * 255 / 300 + n) & 0xFF) << 8 | (120 + n));
            }
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(banner, "png", out);
        byte[] png = out.toByteArray();
        byte[] jpeg = ProfileCommand.toJpeg(png);
        assertTrue(jpeg.length < png.length / 2, "JPEG " + jpeg.length + " vs PNG " + png.length);
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("build", "test-output"));
        java.nio.file.Files.write(java.nio.file.Path.of("build", "test-output", "banner-stats.jpg"), jpeg);
    }
}
