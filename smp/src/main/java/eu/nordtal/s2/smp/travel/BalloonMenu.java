package eu.nordtal.s2.smp.travel;

import eu.nordtal.s2.smp.milestone.Unlock;
import eu.nordtal.s2.smp.world.WorldRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What a balloon shows, worked out without a server so it can be tested as a table.
 *
 * <p>The layout was settled on 2026-09-01 and is a 2 x 2 grid:
 *
 * <pre>
 *   +---------------------------+
 *   |   the OTHER overworld     |   one entry across both upper tiles
 *   +-------------+-------------+
 *   |   Nether    |     End     |   always in that order
 *   +-------------+-------------+
 * </pre>
 *
 * <p>"The other overworld" is what makes one layout work at every balloon: at Nordtal's balloon the
 * wide entry is the farm world, at every other balloon it is Nordtal. Nobody has to learn a second
 * arrangement for the trip home.
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
     * One entry in the grid.
     *
     * @param destination which world it goes to
     * @param state       whether it can be used
     * @param slots       the inventory slots it occupies - two for the wide upper entry, one below
     */
    public record Entry(WorldRole destination, State state, List<Integer> slots) {

        public Entry {
            slots = List.copyOf(slots);
        }

        public boolean travellable() {
            return state == State.OPEN;
        }
    }

    /**
     * The inventory this is drawn into is three rows of nine, and the grid is centred in it: the
     * wide upper entry occupies the two middle slots of the middle row, the two lower ones sit
     * directly beneath. Anything else in the inventory is filler and belongs to no entry.
     *
     * <pre>
     *   row 0   . . . . . . . . .
     *   row 1   . . . 12 13 . . . .     the other overworld, across both
     *   row 2   . . . 21 22 . . . .     Nether  |  End
     * </pre>
     */
    public static final int ROWS = 3;

    private static final List<Integer> TOP = List.of(12, 13);
    private static final int BOTTOM_LEFT = 21;
    private static final int BOTTOM_RIGHT = 22;

    private BalloonMenu() {
    }

    /**
     * Builds the grid for a player standing at the balloon in {@code here}.
     *
     * @param here     the world the balloon stands in
     * @param unlocked which unlocks the completed milestones have handed out
     */
    public static List<Entry> of(final WorldRole here, final Set<Unlock> unlocked) {
        final List<Entry> entries = new ArrayList<>(3);

        // The wide one: always the overworld you are not in.
        final WorldRole other = here == WorldRole.NORDTAL ? WorldRole.FARM : WorldRole.NORDTAL;
        entries.add(new Entry(other, State.OPEN, TOP));

        entries.add(new Entry(WorldRole.NETHER,
                state(here, WorldRole.NETHER, unlocked.contains(Unlock.NETHER)),
                List.of(BOTTOM_LEFT)));
        entries.add(new Entry(WorldRole.END,
                state(here, WorldRole.END, unlocked.contains(Unlock.END)),
                List.of(BOTTOM_RIGHT)));

        return List.copyOf(entries);
    }

    private static State state(final WorldRole here, final WorldRole destination, final boolean open) {
        if (here == destination) {
            return State.HERE;
        }
        return open ? State.OPEN : State.LOCKED;
    }

    /** The entry occupying a clicked slot, or empty for the rest of the inventory. */
    public static java.util.Optional<Entry> at(final List<Entry> entries, final int slot) {
        return entries.stream().filter(entry -> entry.slots().contains(slot)).findFirst();
    }
}
