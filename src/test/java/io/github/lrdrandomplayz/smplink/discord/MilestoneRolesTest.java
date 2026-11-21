package io.github.lrdrandomplayz.smplink.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class MilestoneRolesTest {

    private static final Map<Integer, String> ROLES = new TreeMap<>(Map.of(
            24, "role24", 50, "role50", 100, "role100", 200, "role200"));
    private static final long HOUR = 3600;

    @Test
    void highestOnlyKeepsTheTopRoleAndCanBeNarrowed() {
        Set<String> wanted = MemberSync.milestoneRolesFor(ROLES, true, 125 * HOUR);
        assertEquals(Set.of("role100"), wanted);
        // The sync narrows the set to roles that exist in the server; this threw before 1.8.3.
        wanted.retainAll(Set.of("role24", "role50", "role100", "role200"));
        assertEquals(Set.of("role100"), wanted);
    }

    @Test
    void cumulativeWhenLowerRolesAreKept() {
        assertEquals(Set.of("role24", "role50", "role100"), MemberSync.milestoneRolesFor(ROLES, false, 125 * HOUR));
    }

    @Test
    void noRoleBelowTheFirstMilestone() {
        assertEquals(Set.of(), MemberSync.milestoneRolesFor(ROLES, true, 23 * HOUR));
        assertEquals(Set.of("role24"), MemberSync.milestoneRolesFor(ROLES, true, 24 * HOUR));
    }
}
