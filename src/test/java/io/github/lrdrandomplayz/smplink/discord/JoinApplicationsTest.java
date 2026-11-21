package io.github.lrdrandomplayz.smplink.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.dv8tion.jda.api.components.container.Container;
import org.junit.jupiter.api.Test;

class JoinApplicationsTest {

    private static JoinApplications.Card card() {
        JoinApplications.Card c = new JoinApplications.Card();
        c.discordId = "100000000000000098";
        c.ign = "nakkas1";
        c.edition = "premium";
        c.hours = "around 8 or 9";
        c.style = List.of("Fight", "Grind");
        c.agrees = true;
        c.accountMade = 1_700_000_000L;
        c.history = "first application";
        return c;
    }

    private static String text(Container container) {
        return container.toString();
    }

    @Test
    void pendingCardHasTheButtonsAndAnswers() {
        Container container = JoinApplications.render(card());
        String all = text(container);
        assertTrue(all.contains("app:acc:100000000000000098"));
        assertTrue(all.contains("app:deny:100000000000000098"));
        assertTrue(all.contains("around 8 or 9"));
        assertTrue(all.contains("Fight, Grind"));
        assertEquals(Embeds.BRAND, container.getAccentColorRaw());
    }

    @Test
    void returningPlayerIsMarkedAndAmber() {
        JoinApplications.Card c = card();
        c.bannedBefore = true;
        c.history = Glyph.ALERT + " **Was banned before** for: missing the weekly playtime";
        Container container = JoinApplications.render(c);
        assertTrue(text(container).contains("Was banned before"));
        assertTrue(text(container).contains("Returning player"));
        assertEquals(Embeds.WARNING, container.getAccentColorRaw());
    }

    @Test
    void decidedCardsLoseTheirButtons() {
        JoinApplications.Card c = card();
        c.state = "denied";
        c.decision = "<@1> <t:1:R>";
        c.reason = "Read the rules first";
        Container container = JoinApplications.render(c);
        assertFalse(text(container).contains("app:acc:"));
        assertTrue(text(container).contains("Read the rules first"));
        assertEquals(Embeds.DANGER, container.getAccentColorRaw());
    }

    @Test
    void offMeansEmpty() {
        JoinApplications.Settings s = new JoinApplications.Settings("off", " 123 ", "", "OFF", null, -2);
        assertFalse(s.enabled());
        assertEquals("123", s.staffRoleId());
        assertEquals("✅", s.rulesEmoji());
        assertEquals(0, s.reapplyDays());
    }
}
