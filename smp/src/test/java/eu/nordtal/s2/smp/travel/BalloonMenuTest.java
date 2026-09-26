package eu.nordtal.s2.smp.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.menu.SlotGeometry;
import eu.nordtal.s2.smp.milestone.Unlock;
import eu.nordtal.s2.smp.world.WorldRole;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The balloon's grid as a table, which is what it is.
 *
 * <p>Since 2026-09-05 the rule is that every world has the same card at every balloon and the
 * world the player stands in is marked rather than moved. This is the test that says so, and that
 * the twelve slots under a card are the twelve the panel draws it over.
 *
 * <p>There were four cards until 2026-09-20 and the grid was full; the farm world went with
 * season-2-ingame/30 and the bottom right is a hole now. That is the one thing this file gained
 * rather than lost: a click into the hole has to hit nothing, exactly like a click into the gap
 * column, and nothing else proves it.
 */
class BalloonMenuTest {

    private static final Set<Unlock> NOTHING = EnumSet.noneOf(Unlock.class);
    private static final Set<Unlock> BOTH = EnumSet.of(Unlock.NETHER, Unlock.END);

    /** The two worlds a balloon actually stands in. The End has none and Nordtal's is the first. */
    private static final List<WorldRole> BALLOONS = List.of(WorldRole.NORDTAL, WorldRole.NETHER);

    @Test
    @DisplayName("the three cards are in the same places at every balloon")
    void theCardsNeverMove() {
        for (final WorldRole here : BALLOONS) {
            final List<BalloonMenu.Entry> entries = BalloonMenu.of(here, BOTH);
            assertEquals(
                    List.of(WorldRole.NORDTAL, WorldRole.NETHER, WorldRole.END),
                    entries.stream().map(BalloonMenu.Entry::destination).toList(),
                    "at " + here + " the cards are not Nordtal, Nether / End");
            assertEquals(
                    List.of(0, 1, 0),
                    entries.stream().map(BalloonMenu.Entry::column).toList());
            assertEquals(
                    List.of(0, 0, 1),
                    entries.stream().map(BalloonMenu.Entry::row).toList());
        }
    }

    @Test
    @DisplayName("a card covers three rows of four slots, and column 4 is the gap")
    void aCardCoversTwelveSlots() {
        for (final BalloonMenu.Entry entry : BalloonMenu.of(WorldRole.NORDTAL, BOTH)) {
            assertEquals(
                    BalloonMenu.CARD_ROWS * BalloonMenu.CARD_COLUMNS,
                    entry.slots().size());
            for (final int slot : entry.slots()) {
                assertTrue(SlotGeometry.column(slot) != 4, "slot " + slot + " is in the gap column");
                assertEquals(entry.row(), SlotGeometry.row(slot) / BalloonMenu.CARD_ROWS);
                assertEquals(entry.column(), SlotGeometry.column(slot) < 4 ? 0 : 1);
            }
        }
    }

    @Test
    @DisplayName("the world you are in is shown, marked, and not travellable")
    void theWorldYouAreInIsHere() {
        for (final WorldRole here : BALLOONS) {
            final List<BalloonMenu.Entry> entries = BalloonMenu.of(here, BOTH);
            final List<WorldRole> marked = entries.stream()
                    .filter(entry -> entry.state() == BalloonMenu.State.HERE)
                    .map(BalloonMenu.Entry::destination)
                    .toList();
            assertEquals(List.of(here), marked, "exactly the world the balloon stands in is HERE");
            assertTrue(entries.stream()
                    .noneMatch(entry -> entry.state() == BalloonMenu.State.HERE && entry.travellable()));
        }
    }

    /** Locked destinations keep their place rather than disappearing - that is the whole point. */
    @Test
    void aLockedDestinationStaysInTheGrid() {
        final List<BalloonMenu.Entry> entries = BalloonMenu.of(WorldRole.NORDTAL, NOTHING);

        assertEquals(3, entries.size());
        assertEquals(BalloonMenu.State.LOCKED, entries.get(1).state());
        assertEquals(BalloonMenu.State.LOCKED, entries.get(2).state());
    }

    @Test
    void unlockingOneDoesNotUnlockTheOther() {
        final List<BalloonMenu.Entry> entries = BalloonMenu.of(WorldRole.NORDTAL, EnumSet.of(Unlock.NETHER));

        assertEquals(BalloonMenu.State.OPEN, entries.get(1).state());
        assertEquals(BalloonMenu.State.LOCKED, entries.get(2).state());
    }

    @Test
    @DisplayName("Nordtal is never locked, wherever the balloon stands")
    void nordtalIsNeverLocked() {
        for (final WorldRole here : BALLOONS) {
            for (final BalloonMenu.Entry entry : BalloonMenu.of(here, NOTHING)) {
                if (entry.destination() == WorldRole.NORDTAL) {
                    assertTrue(
                            entry.state() != BalloonMenu.State.LOCKED, entry.destination() + " is locked at " + here);
                }
            }
        }
    }

    @Test
    void noTwoEntriesShareASlot() {
        for (final WorldRole here : BALLOONS) {
            final List<Integer> slots = BalloonMenu.of(here, BOTH).stream()
                    .flatMap(entry -> entry.slots().stream())
                    .toList();
            assertEquals(slots.size(), Set.copyOf(slots).size(), "two entries would draw over each other at " + here);
            assertEquals(36, slots.size(), "three cards of twelve, and the bottom right is empty");
        }
    }

    @Test
    void aClickInTheGapHitsNothing() {
        final List<BalloonMenu.Entry> entries = BalloonMenu.of(WorldRole.NORDTAL, BOTH);

        for (int row = 0; row < BalloonMenu.ROWS; row++) {
            assertTrue(
                    BalloonMenu.at(entries, SlotGeometry.slot(4, row)).isEmpty(),
                    "column 4 of row " + row + " belongs to no card");
        }
        assertEquals(WorldRole.NORDTAL, BalloonMenu.at(entries, 0).orElseThrow().destination());
        assertEquals(WorldRole.NETHER, BalloonMenu.at(entries, 8).orElseThrow().destination());
        assertEquals(WorldRole.END, BalloonMenu.at(entries, 45).orElseThrow().destination());
    }

    @Test
    @DisplayName("the empty bottom right belongs to no card either")
    void aClickInTheHoleHitsNothing() {
        final List<BalloonMenu.Entry> entries = BalloonMenu.of(WorldRole.NORDTAL, BOTH);

        for (int row = BalloonMenu.CARD_ROWS; row < BalloonMenu.ROWS; row++) {
            for (int column = 5; column < 9; column++) {
                final int slot = SlotGeometry.slot(column, row);
                assertTrue(
                        BalloonMenu.at(entries, slot).isEmpty(),
                        "slot " + slot + " is where the farm world's card used to be");
            }
        }
    }
}
