package io.github.lrdrandomplayz.smplink.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LinkCodesTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final LinkCodes codes = new LinkCodes(600_000, now::get);
    private final UUID steve = UUID.randomUUID();

    @Test
    void aCodeWorksOnceAndIgnoresCaseSpacesAndDashes() {
        String code = codes.create(steve, "Steve");
        assertEquals(LinkCodes.LENGTH, code.length());
        String typed = " " + code.substring(0, 3).toLowerCase() + "-" + code.substring(3) + " ";
        assertEquals("Steve", codes.redeem(typed).orElseThrow().name());
        assertTrue(codes.redeem(code).isEmpty(), "used up");
    }

    @Test
    void codesExpire() {
        String code = codes.create(steve, "Steve");
        now.addAndGet(600_000);
        assertTrue(codes.redeem(code).isEmpty());
    }

    @Test
    void aNewCodeReplacesTheOldOne() {
        String first = codes.create(steve, "Steve");
        String second = codes.create(steve, "Steve");
        assertNotEquals(first, second);
        assertTrue(codes.redeem(first).isEmpty());
        assertEquals(steve, codes.redeem(second).orElseThrow().uuid());
    }

    @Test
    void codesAvoidLookAlikeCharacters() {
        for (int i = 0; i < 200; i++) {
            assertTrue(codes.create(UUID.randomUUID(), "p").matches("[A-HJKMNP-Z2-9]{6}"));
        }
    }

    @Test
    void unknownCodesAreRefused() {
        assertTrue(codes.redeem("ZZZZZZ").isEmpty());
        assertTrue(codes.redeem(null).isEmpty());
    }
}
