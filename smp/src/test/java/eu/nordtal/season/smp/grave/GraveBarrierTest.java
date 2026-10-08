package eu.nordtal.season.smp.grave;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.smp.grave.GraveBarrier.There;
import org.junit.jupiter.api.Test;

/** The barrier that makes a grave solid, and that it never costs anybody a block. */
class GraveBarrierTest {

    @Test
    void aGraveInEmptyAirTakesTheBarrier() {
        assertTrue(GraveBarrier.placesInto(There.EMPTY));
    }

    @Test
    void aGraveNeverReplacesTheBlockThatIsThere() {
        assertFalse(GraveBarrier.placesInto(There.OTHER), "a block, a plant or water in the grave's place stays");
    }

    @Test
    void aBarrierFromBeforeTheRestartIsKeptAsItIs() {
        assertFalse(GraveBarrier.placesInto(There.BARRIER));
    }

    @Test
    void aGraveThatIsGoneClearsItsBarrier() {
        assertTrue(GraveBarrier.clears(There.BARRIER, false));
    }

    @Test
    void aGraveThatIsGoneLeavesTheBarrierOfAnotherGraveInTheSameBlock() {
        assertFalse(GraveBarrier.clears(There.BARRIER, true));
    }

    @Test
    void aGraveThatIsGoneNeverClearsAnythingButABarrier() {
        assertFalse(GraveBarrier.clears(There.OTHER, false), "a block placed there since stays");
        assertFalse(GraveBarrier.clears(There.EMPTY, false));
    }
}
