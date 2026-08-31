package eu.nordtal.s2.smp.milestone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Advancing an objective, and the one moment that fires everything else.
 *
 * <p>"Did that hand-in complete the objective" is the question behind the payout, the announcement,
 * the milestone unlock and possibly a border move. It has to be true <b>exactly once</b>: an
 * objective that completed twice would pay its pot twice.
 */
class ObjectiveProgressTest {

    @Test
    void addingBelowTheTargetDoesNotComplete() {
        final ObjectiveProgress.Advance advance = ObjectiveProgress.advance(100, 2048, 400);

        assertEquals(500, advance.amount());
        assertEquals(400, advance.credited());
        assertFalse(advance.completes());
    }

    @Test
    void crossingTheTargetCompletesExactlyOnce() {
        final ObjectiveProgress.Advance crossing = ObjectiveProgress.advance(2000, 2048, 100);
        assertTrue(crossing.completes());
        assertEquals(2100, crossing.amount(), "an overshoot is kept, not clipped to the target");

        // The next delivery against an objective that is already finished must not complete it
        // again. This is the whole reason `completes` is computed from the PREVIOUS amount.
        assertFalse(ObjectiveProgress.advance(2100, 2048, 100).completes());
    }

    @Test
    void landingExactlyOnTheTargetCompletes() {
        assertTrue(ObjectiveProgress.advance(2000, 2048, 48).completes());
    }

    @Test
    void nothingIsCreditedForNothing() {
        assertEquals(0, ObjectiveProgress.advance(100, 2048, 0).credited());
        assertEquals(100, ObjectiveProgress.advance(100, 2048, 0).amount());
        assertEquals(100, ObjectiveProgress.advance(100, 2048, -50).amount(),
                "a negative delta credits nothing rather than taking progress away");
    }

    @Test
    void anAbsurdDeltaSaturatesRatherThanWrapping() {
        // Nothing on this server can reach a bigint's limit, and an overflowing counter would read
        // as an objective going backwards - which is worse than a number nobody will ever see.
        assertEquals(Long.MAX_VALUE, ObjectiveProgress.advance(Long.MAX_VALUE - 1, 100, 1000).amount());
    }

    @Test
    void aLoweredTargetCompletesAnObjectiveOnReload() {
        // The first escape hatch: "if the progress already collected is at or above the new target,
        // the objective completes at once and pays normally" - the FULL pot, because nothing was
        // rescued; the number was simply wrong when it was written.
        assertTrue(ObjectiveProgress.completesOnReload(1500, 1000));
        assertTrue(ObjectiveProgress.completesOnReload(1000, 1000));
        assertFalse(ObjectiveProgress.completesOnReload(999, 1000));
        assertFalse(ObjectiveProgress.completesOnReload(0, 1000));
    }

    @Test
    void thePercentageIsWhatABoardWouldPrint() {
        assertEquals(0, ObjectiveProgress.percentOf(0, 2048));
        assertEquals(50, ObjectiveProgress.percentOf(1024, 2048));
        assertEquals(99, ObjectiveProgress.percentOf(2047, 2048), "floored, so 100 % means done");
        assertEquals(100, ObjectiveProgress.percentOf(2048, 2048));
        assertEquals(100, ObjectiveProgress.percentOf(9000, 2048), "an overshoot still reads 100");
    }
}
