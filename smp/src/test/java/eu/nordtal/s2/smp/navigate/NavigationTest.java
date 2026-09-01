package eu.nordtal.s2.smp.navigate;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who is navigating where, and - more importantly - who has stopped.
 *
 * <p>{@code /navigate} is off by default and switched on deliberately, so "nobody is navigating" is
 * the normal state rather than a missing value, and HUD line 2 exists only while it is not.
 */
class NavigationTest {

    private final Navigation navigation = new Navigation();
    private final UUID player = UUID.randomUUID();

    @Test
    void nobodyIsNavigatingUntilTheyAsk() {
        assertFalse(navigation.isNavigating(player));
        assertTrue(navigation.of(player).isEmpty());
    }

    @Test
    void aTargetIsRememberedUntilItIsCleared() {
        final NavigationTarget target = NavigationTarget.worldSpawn("nordtal", 106, 70, 88);
        navigation.set(player, target);

        assertTrue(navigation.isNavigating(player));
        assertEquals(target, navigation.of(player).orElseThrow());

        navigation.clear(player);
        assertFalse(navigation.isNavigating(player));
    }

    /**
     * The farm world is regenerated daily and nothing in it survives. An arrow that outlived its
     * target would still point confidently at terrain that no longer exists.
     */
    @Test
    void clearingAWorldDropsOnlyTheTargetsInIt() {
        final UUID other = UUID.randomUUID();
        navigation.set(player, NavigationTarget.poi(UUID.randomUUID(), "the mine", "farm", 10, 60, 10));
        navigation.set(other, NavigationTarget.poi(UUID.randomUUID(), "the tavern", "nordtal", 106, 70, 88));

        navigation.clearWorld("farm");

        assertFalse(navigation.isNavigating(player));
        assertTrue(navigation.isNavigating(other), "Nordtal's targets are untouched");
        assertEquals(1, navigation.size());
    }

    @Test
    void aTargetKnowsWhichWorldItIsIn() {
        final NavigationTarget target = NavigationTarget.lastDeath("nordtal_nether", 8, 40, 8);

        assertTrue(target.isIn("nordtal_nether"));
        assertFalse(target.isIn("nordtal"), "the same numbers in another world are another place");
        assertEquals(NavigationTarget.Kind.LAST_DEATH, target.kind());
    }
}
