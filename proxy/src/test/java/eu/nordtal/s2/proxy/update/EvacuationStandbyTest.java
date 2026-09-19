package eu.nordtal.s2.proxy.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Which waiting room a run can use (season-2-ops/120).
 *
 * <p>The case this exists for is the one {@link Evacuation} had no answer to until now: a run that
 * stops the waiting room itself. Everybody connected was disconnected and the countdown was all the
 * warning they got. {@code limbo-standby} is the answer, and the four cases below are the whole of
 * the rule - which is why they are held here rather than left to a reviewer noticing the {@code if}
 * order is the right way round.</p>
 *
 * <p>No proxy and no database: {@link Evacuation#roomFor} takes a predicate for "is this registered
 * here", so the decision can be exercised without a running Velocity.</p>
 */
class EvacuationStandbyTest {

    private static final String LIMBO = "limbo";
    private static final String STANDBY = "limbo-standby";

    /** A proxy that has both rooms, which is what a swap looks like from the inside. */
    private static final Predicate<String> BOTH = name -> LIMBO.equals(name) || STANDBY.equals(name);

    /** The ordinary proxy: no standby is running, because nothing is being swapped. */
    private static final Predicate<String> ONLY_LIMBO = LIMBO::equals;

    @Test
    @DisplayName("an ordinary run keeps using the waiting room, standby or no standby")
    void anOrdinaryRunUsesTheWaitingRoom() {
        // Both up and the run leaves the limbo alone: the live room wins. Preferring the standby
        // here would split arrivals across two rooms for no reason at all.
        assertEquals(LIMBO, Evacuation.roomFor(Set.of("smp"), LIMBO, STANDBY, BOTH));
        assertEquals(LIMBO, Evacuation.roomFor(Set.of("smp"), LIMBO, STANDBY, ONLY_LIMBO));
    }

    @Test
    @DisplayName("a run that stops the waiting room moves people to the standby instead of giving up")
    void aRunOnTheWaitingRoomUsesTheStandby() {
        // THE WHOLE TICKET, in one assertion. Before season-2-ops/120 this answered "nowhere".
        assertEquals(STANDBY, Evacuation.roomFor(Set.of(LIMBO), LIMBO, STANDBY, BOTH));
        assertEquals(STANDBY, Evacuation.roomFor(Set.of(LIMBO, "smp"), LIMBO, STANDBY, BOTH));
    }

    @Test
    @DisplayName("no standby registered is still the old answer, and it is still nowhere")
    void withoutAStandbyThereIsStillNowhereToGo() {
        // Not a regression: a proxy without the standby profile running behaves exactly as it did
        // before the ticket, and Evacuation says so in the log rather than moving anybody onto a
        // server that does not exist.
        assertNull(Evacuation.roomFor(Set.of(LIMBO), LIMBO, STANDBY, ONLY_LIMBO));
    }

    @Test
    @DisplayName("a run that stops both rooms is nowhere too, rather than a move onto a stopping server")
    void aRunOnBothRoomsIsNowhere() {
        // The run has taken the ground out from under itself. Moving players into the standby
        // would be worse than not moving them: they would be disconnected seconds later from a
        // server nobody told them about, instead of from the one they chose to be on.
        assertNull(Evacuation.roomFor(Set.of(LIMBO, STANDBY), LIMBO, STANDBY, BOTH));
    }
}
