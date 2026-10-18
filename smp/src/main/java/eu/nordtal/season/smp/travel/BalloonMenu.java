package eu.nordtal.season.smp.travel;

import eu.nordtal.season.papercommon.menu.SlotGeometry;
import eu.nordtal.season.smp.milestone.Unlock;
import eu.nordtal.season.smp.world.WorldRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** No state moves or hides a card. */
public final class BalloonMenu {

    public enum State {
        /** The world of the balloon. It is never a travel target. */
        HERE,
        OPEN,
        /** The milestone of the world is not finished. The card is greyed and shows the reason. */
        LOCKED
    }

    /**
     * One card of the grid.
     *
     * @param destination the world the card travels to
     * @param state       whether the card can be used
     * @param column      0 for the left card, 1 for the right
     * @param row         0 for the upper card, 1 for the lower
     * @param slots       the twelve inventory slots of the card
     */
    public record Entry(WorldRole destination, State state, int column, int row, List<Integer> slots) {

        public Entry {
            slots = List.copyOf(slots);
        }

        public boolean travellable() {
            return state == State.OPEN;
        }
    }

    /** The inventory has six rows. The cards cover every slot except column 4 and the empty cell of the grid. */
    public static final int ROWS = 6;

    public static final int CARD_ROWS = 3;

    public static final int CARD_COLUMNS = 4;

    private static final int[] CARD_COLUMN_START = {0, 5};

    /** The cards in the 2 x 2 grid, row by row. The empty cell comes last. */
    private static final WorldRole[][] PLACES = {
        {WorldRole.NORDTAL, WorldRole.NETHER},
        {WorldRole.END},
    };

    private BalloonMenu() {}

    /**
     * Returns the cards for a balloon in {@code here}.
     *
     * @param here     the world of the balloon
     * @param unlocked the unlocks that completed milestones grant
     */
    public static List<Entry> of(final WorldRole here, final Set<Unlock> unlocked) {
        final List<Entry> entries = new ArrayList<>(3);
        for (int row = 0; row < PLACES.length; row++) {
            for (int column = 0; column < PLACES[row].length; column++) {
                final WorldRole destination = PLACES[row][column];
                entries.add(
                        new Entry(destination, state(here, destination, unlocked), column, row, slots(column, row)));
            }
        }
        return List.copyOf(entries);
    }

    public static int slotColumn(final int column) {
        return CARD_COLUMN_START[column];
    }

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
            case NORDTAL -> State.OPEN;
        };
    }

    public static Optional<Entry> at(final List<Entry> entries, final int slot) {
        return entries.stream().filter(entry -> entry.slots().contains(slot)).findFirst();
    }
}
