package io.github.lrdrandomplayz.smplink.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class StrikesTest {

    @Test
    void expiryReadsInMonthsWhenItCan() {
        assertEquals("3 months", Strikes.expiryText(90));
        assertEquals("1 month", Strikes.expiryText(30));
        assertEquals("45 days", Strikes.expiryText(45));
        assertEquals("1 day", Strikes.expiryText(1));
    }

    @Test
    void vipDonorAndMinecraftAdminAreAlwaysExempt() {
        // Roles always exempt are added after the configured ones, without duplicates or blanks.
        assertEquals(List.of("donor", "vip", "admin"), Strikes.exemptRoles(List.of("donor"), "vip", "admin"));
        // Listed twice or left blank, they aren't doubled or added as empty.
        assertEquals(List.of("donor", "vip"), Strikes.exemptRoles(List.of("donor", "vip"), "vip", " "));
    }
}
