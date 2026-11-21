package io.github.lrdrandomplayz.smplink.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.dv8tion.jda.api.entities.MessageEmbed;
import io.github.lrdrandomplayz.smplink.service.PlayerService.Period;
import io.github.lrdrandomplayz.smplink.service.PlayerService.Playtime;
import io.github.lrdrandomplayz.smplink.service.PlayerService.Ranking;
import io.github.lrdrandomplayz.smplink.service.PlayerService.Standing;
import org.junit.jupiter.api.Test;

/** Discord only renders <t:…> timestamps in descriptions and field values, never in titles, field names or footers. */
class EmbedTimestampTest {

    private static final long NOW = System.currentTimeMillis();
    private static final long DAY = 24L * 60 * 60 * 1000;

    private static void assertTimestampsOnlyWhereTheyRender(MessageEmbed embed) {
        assertFalse(String.valueOf(embed.getTitle()).contains("<t:"), "title");
        assertFalse(embed.getFooter() != null && embed.getFooter().getText().contains("<t:"), "footer");
        embed.getFields().forEach(field -> assertFalse(String.valueOf(field.getName()).contains("<t:"), "field name " + field.getName()));
    }

    @Test
    void playtimePageUsesTimestamps() {
        Standing standing = new Standing(3, 54, "100000000000000099", UUID.randomUUID(), "Steve", true,
                291_325, 12_000, 40_000, NOW - 2 * DAY, false, NOW - 60 * DAY, NOW, NOW - 5 * 60_000);
        String text = ProfileTest.text(ProfileCommand.playtimeBody("Alex", standing));

        assertTrue(text.contains("**Online** since <t:"), text);
        assertTrue(text.contains("rank **#3** of 54"), text);
        // Recording started 2 days ago, so both windows count from then rather than 7/30 days back.
        assertTrue(text.contains("Last 7 days **3 h 20 m** · since <t:" + (NOW - 2 * DAY) / 1000 + ":d>"), text);
    }

    @Test
    void quotaBarFillsWithPlaytime() {
        assertEquals("`██████░░░░░░` 50%", Embeds.quotaBar(5_400, 10_800, 12));
        assertEquals("`░░░░░░░░░░` 0%", Embeds.quotaBar(0, 10_800, 10));
        // Over the quota stays full rather than overflowing the bar.
        assertEquals("`██████████` 100%", Embeds.quotaBar(20_000, 10_800, 10));
    }

    @Test
    void weeklyLeaderboardSaysWhichPeriod() {
        Ranking board = new Ranking(Period.WEEKLY, 1, 0, NOW - 30 * DAY, NOW - 7 * DAY,
                List.of(new Playtime(1, "100000000000000099", UUID.randomUUID(), "Steve", false, 7_200)));
        MessageEmbed embed = Embeds.leaderboard(board, 0, 1);

        assertTrue(embed.getDescription().startsWith("**Weekly** · <t:"), embed.getDescription());
        assertFalse(embed.getDescription().contains("recorded since then"), "a full week is recorded");
        assertTimestampsOnlyWhereTheyRender(embed);
    }
}
