package eu.nordtal.s2.smp.travel;

import eu.nordtal.s2.smp.milestone.Unlock;
import eu.nordtal.s2.smp.world.WorldRole;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The balloon's grid as a table, which is what it is.
 *
 * <p>The layout rule that makes one arrangement work at every balloon is that the wide upper entry
 * is always <em>the overworld you are not in</em>. Nobody has to learn a second arrangement for the
 * trip home, and this is the test that says so.
 */
class BalloonMenuTest {

    private static final Set<Unlock> NOTHING = EnumSet.noneOf(Unlock.class);
    private static final Set<Unlock> BOTH = EnumSet.of(Unlock.NETHER, Unlock.END);

    @Test
    void theWideEntryIsAlwaysTheOtherOverworld() {
        assertEquals(WorldRole.FARM, BalloonMenu.of(WorldRole.NORDTAL, NOTHING).get(0).destination());
        assertEquals(WorldRole.NORDTAL, BalloonMenu.of(WorldRole.FARM, NOTHING).get(0).destination());
        assertEquals(WorldRole.NORDTAL, BalloonMenu.of(WorldRole.NETHER, NOTHING).get(0).destination());
    }

    @Test
    void theLowerTwoAreAlwaysNetherThenEnd() {
        final List<BalloonMenu.Entry> entries = BalloonMenu.of(WorldRole.NORDTAL, BOTH);

        assertEquals(WorldRole.NETHER, entries.get(1).destination());
        assertEquals(WorldRole.END, entries.get(2).destination());
        assertTrue(entries.get(1).slots().get(0) < entries.get(2).slots().get(0),
                "the Nether is on the left");
    }

    @Test
    void theWideEntryOccupiesTwoSlotsAndTheOthersOne() {
        final List<BalloonMenu.Entry> entries = BalloonMenu.of(WorldRole.NORDTAL, BOTH);

        assertEquals(2, entries.get(0).slots().size());
        assertEquals(1, entries.get(1).slots().size());
        assertEquals(1, entries.get(2).slots().size());
    }

    /** Locked destinations keep their place rather than disappearing - that is the whole point. */
    @Test
    void aLockedDestinationStaysInTheGrid() {
        final List<BalloonMenu.Entry> entries = BalloonMenu.of(WorldRole.NORDTAL, NOTHING);

        assertEquals(3, entries.size());
        assertEquals(BalloonMenu.State.LOCKED, entries.get(1).state());
        assertEquals(BalloonMenu.State.LOCKED, entries.get(2).state());
        assertEquals(BalloonMenu.State.OPEN, entries.get(0).state(),
                "the farm world is withheld by the border, not by a lock in this menu");
    }

    @Test
    void unlockingOneDoesNotUnlockTheOther() {
        final List<BalloonMenu.Entry> entries =
                BalloonMenu.of(WorldRole.NORDTAL, EnumSet.of(Unlock.NETHER));

        assertEquals(BalloonMenu.State.OPEN, entries.get(1).state());
        assertEquals(BalloonMenu.State.LOCKED, entries.get(2).state());
    }

    @Test
    void theWorldYouAreInIsShownButNotTravellable() {
        final List<BalloonMenu.Entry> entries = BalloonMenu.of(WorldRole.NETHER, BOTH);

        assertEquals(BalloonMenu.State.HERE, entries.get(1).state());
        assertTrue(entries.stream().noneMatch(
                entry -> entry.state() == BalloonMenu.State.HERE && entry.travellable()));
    }

    @Test
    void noTwoEntriesShareASlot() {
        for (final WorldRole here : List.of(WorldRole.NORDTAL, WorldRole.FARM, WorldRole.NETHER)) {
            final List<Integer> slots = BalloonMenu.of(here, BOTH).stream()
                    .flatMap(entry -> entry.slots().stream())
                    .toList();
            assertEquals(slots.size(), Set.copyOf(slots).size(),
                    "two entries would draw over each other at " + here);
        }
    }

    @Test
    void aClickOnFillerHitsNothing() {
        final List<BalloonMenu.Entry> entries = BalloonMenu.of(WorldRole.NORDTAL, BOTH);

        assertTrue(BalloonMenu.at(entries, 0).isEmpty());
        assertTrue(BalloonMenu.at(entries, 26).isEmpty());
        assertTrue(BalloonMenu.at(entries, 12).isPresent());
        assertTrue(BalloonMenu.at(entries, 13).isPresent());
        assertEquals(BalloonMenu.at(entries, 12).orElseThrow(),
                BalloonMenu.at(entries, 13).orElseThrow(),
                "both upper slots are one entry, not two");
    }
}
