package io.github.lrdrandomplayz.smplink.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LinkedRoleSweepTest {

    @Test
    void onlyUnlinkedHoldersLoseTheLinkedRole() {
        // 1 and 3 are linked; 2 was unlinked (or given the role by hand) and 4 is listed twice by Discord.
        assertEquals(List.of("2", "4"), MemberSync.strays(List.of("1", "2", "3", "4", "4"), Set.of("1", "3")));
        assertEquals(List.of(), MemberSync.strays(List.of("1"), Set.of("1", "2")));
    }
}
