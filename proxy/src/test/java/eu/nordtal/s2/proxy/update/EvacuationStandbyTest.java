package eu.nordtal.s2.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** Which waiting room a run can use, including a run that stops {@code limbo} itself. */
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
        // Both up and the run leaves limbo alone: the live room wins.
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
        // Without a standby, nobody is moved; Evacuation logs it.
        assertNull(Evacuation.roomFor(Set.of(LIMBO), LIMBO, STANDBY, ONLY_LIMBO));
    }

    @Test
    void aRunOnBothRoomsIsNowhere() {
        // Moving players into the standby here would disconnect them seconds later.
        assertNull(Evacuation.roomFor(Set.of(LIMBO, STANDBY), LIMBO, STANDBY, EvacuationStandbyTest::both));
    }
}
