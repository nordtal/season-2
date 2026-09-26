package eu.nordtal.s2.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Which waiting room a run can use.
 *
 * The case this exists for is the one {@link Evacuation} had no answer to until now: a run that
 * stops the waiting room itself. Everybody connected was disconnected and the countdown was all the
 * warning they got. {@code limbo-standby} is the answer, and the four cases below are the whole of
 * the rule - which is why they are held here rather than left to a reviewer noticing the {@code if}
 * order is the right way round.
 *
 * No proxy and no database: {@link Evacuation#roomFor} takes a predicate for "is this registered
 * here", so the decision can be exercised without a running Velocity.
 */
class EvacuationStandbyTest {

    private static final String LIMBO = "limbo";
    private static final String STANDBY = "limbo-standby";

    /** A proxy that has both rooms, which is what a swap looks like from the inside. */
    private static boolean both(final String name) {
        return LIMBO.equals(name) || STANDBY.equals(name);
    }

    /** The ordinary proxy: no standby is running, because nothing is being swapped. */
    private static final Predicate<String> ONLY_LIMBO = LIMBO::equals;

    @Test
    void anOrdinaryRunUsesTheWaitingRoom() {
        // Both up and the run leaves the limbo alone: the live room wins over splitting arrivals for no reason.
        assertEquals(LIMBO, Evacuation.roomFor(Set.of("smp"), LIMBO, STANDBY, EvacuationStandbyTest::both));
        assertEquals(LIMBO, Evacuation.roomFor(Set.of("smp"), LIMBO, STANDBY, ONLY_LIMBO));
    }

    @Test
    void aRunOnTheWaitingRoomUsesTheStandby() {
        assertEquals(STANDBY, Evacuation.roomFor(Set.of(LIMBO), LIMBO, STANDBY, EvacuationStandbyTest::both));
        assertEquals(STANDBY, Evacuation.roomFor(Set.of(LIMBO, "smp"), LIMBO, STANDBY, EvacuationStandbyTest::both));
    }

    @Test
    void withoutAStandbyThereIsStillNowhereToGo() {
        // A proxy without the standby profile gets nothing; Evacuation logs it rather than moving anybody onto air.
        assertNull(Evacuation.roomFor(Set.of(LIMBO), LIMBO, STANDBY, ONLY_LIMBO));
    }

    @Test
    void aRunOnBothRoomsIsNowhere() {
        // Moving players into the standby here disconnects them seconds later from a server nobody told them about.
        assertNull(Evacuation.roomFor(Set.of(LIMBO, STANDBY), LIMBO, STANDBY, EvacuationStandbyTest::both));
    }
}
