package eu.nordtal.s2.smp.travel;

import eu.nordtal.s2.common.menu.SlotGeometry;
import eu.nordtal.s2.smp.milestone.Unlock;
import eu.nordtal.s2.smp.world.WorldRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a balloon shows, worked out without a server so it can be tested as a table.
 *
 * <p>The layout was settled on 2026-09-01 as "the other overworld, wide, above Nether | End" and
 * re-settled on 2026-09-05 (owner's call) as four equal cards in fixed places, in the manner of
 * Origin Realms' travel menu:
 *
 * <pre>
 *   +-------------+   +-------------+
 *   |   Nordtal   |   |  farm world |   rows 0-2, columns 0-3 and 5-8
 *   +-------------+   +-------------+
 *   |   Nether    |   |     End     |   rows 3-5, the same columns
 *   +-------------+   +-------------+
 * </pre>
 *
 * <p>Every world has the same place at every balloon, and the card of the world the player is
 * standing in is marked rather than moved. That is what the fixed places buy: nobody has to learn
 * where "home" went, because it never goes anywhere.
 *
 * <p>A destination that is not unlocked yet <b>stays in its place, greyed</b>, naming the milestone
 * that opens it and pointing at the objective board - rather than disappearing. The moment somebody
 * stands at the balloon is the moment they want to know why the Nether is not available, and an
 * entry that is simply absent answers nothing.
 *
 * <p>The End has no balloon of its own, so it is never the "here" world: the way out is the vanilla
 * exit portal, which does not work until the dragon is dead. That asymmetry is intended and is the
 * point of unlocking the End together.
 */
public final class BalloonMenu {

    /** Where a balloon can send somebody, and whether it can right now. */
    public enum State {
        /** The world the player is standing in. Shown, never travelled to. */
        HERE,
        /** Unlocked and one click away. */
        OPEN,
        /** Its milestone is not finished. Greyed, in place, with the reason. */
        LOCKED
    }

    /**
     * One card in the grid.
     *
     * @param destination which world it goes to
     * @param state       whether it can be used
     * @param column      0 for the left card, 1 for the right
     * @param row         0 for the upper card, 1 for the lower
     * @param slots       the twelve inventory slots the card covers
     */
    public record Entry(WorldRole destination, State state, int column, int row, List<Integer> slots) {

        public Entry {
            slots = List.copyOf(slots);
        }

        public boolean travellable() {
            return state == State.OPEN;
        }
    }

    /** The inventory is six rows of nine; the cards cover all of it but column 4. */
    public static final int ROWS = 6;

    /** A card is three slot rows tall and four slot columns wide. */
    public static final int CARD_ROWS = 3;
    public static final int CARD_COLUMNS = 4;

    /** The slot column each card column starts at: 0..3 and 5..8, leaving 4 as the gap. */
    private static final int[] CARD_COLUMN_START = {0, 5};

    /** The four cards' fixed places: (column, row) in the 2 x 2 grid. */
    private static final WorldRole[][] PLACES = {
            {WorldRole.NORDTAL, WorldRole.FARM},
            {WorldRole.NETHER, WorldRole.END},
    };

    private BalloonMenu() {
    }

    /**
     * Builds the grid for a player standing at the balloon in {@code here}.
     *
     * @param here     the world the balloon stands in
     * @param unlocked which unlocks the completed milestones have handed out
     */
    public static List<Entry> of(final WorldRole here, final Set<Unlock> unlocked) {
        final List<Entry> entries = new ArrayList<>(4);
        for (int row = 0; row < PLACES.length; row++) {
            for (int column = 0; column < PLACES[row].length; column++) {
                final WorldRole destination = PLACES[row][column];
                entries.add(new Entry(destination, state(here, destination, unlocked),
                        column, row, slots(column, row)));
            }
        }
        return List.copyOf(entries);
    }

    /** The slot column a card column starts at - the left edge of its clickable area. */
    public static int slotColumn(final int column) {
        return CARD_COLUMN_START[column];
    }

    /** The slot row a card row starts at. */
    public static int slotRow(final int row) {
        return row * CARD_ROWS;
    }

    private static List<Integer> slots(final int column, final int row) {
        final List<Integer> slots = new ArrayList<>(CARD_ROWS * CARD_COLUMNS);
        for (int r = 0; r < CARD_ROWS; r++) {
            for (int c = 0; c < CARD_COLUMNS; c++) {
                slots.add(SlotGeometry.slot(slotColumn(column) + c, slotRow(row) + r));
            }
        }
        return slots;
    }

    private static State state(final WorldRole here, final WorldRole destination, final Set<Unlock> unlocked) {
        if (here == destination) {
            return State.HERE;
        }
        return switch (destination) {
            case NETHER -> unlocked.contains(Unlock.NETHER) ? State.OPEN : State.LOCKED;
            case END -> unlocked.contains(Unlock.END) ? State.OPEN : State.LOCKED;
            // The two overworlds are never locked here: until the opening expansion the farm world
            // is withheld by the border, not by this menu - see docs/smp.md#the-balloon-gui.
            case NORDTAL, FARM -> State.OPEN;
        };
    }

    /** The card occupying a clicked slot, or empty for the gap column. */
    public static Optional<Entry> at(final List<Entry> entries, final int slot) {
        return entries.stream().filter(entry -> entry.slots().contains(slot)).findFirst();
    }
}
