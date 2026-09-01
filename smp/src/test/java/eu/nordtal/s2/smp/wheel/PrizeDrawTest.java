package eu.nordtal.s2.smp.wheel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wheel's weighted draw, and the thresholds that earn extra spins.
 *
 * <p>The wheel is the only reward channel in this design that pays out actual items, so it is the
 * one worth abusing and the one worth getting arithmetically right. Given a seeded {@link Random},
 * "roughly the right distribution" becomes something that can be asserted rather than hoped for.
 */
class PrizeDrawTest {

    @Test
    void aSingleEntryAlwaysWins() {
        assertEquals(0, PrizeDraw.draw(List.of(7), new Random(1)));
    }

    @Test
    void weightZeroIsNeverDrawn() {
        final List<Integer> weights = List.of(0, 5, 0);
        final Random random = new Random(42);
        for (int i = 0; i < 500; i++) {
            assertEquals(1, PrizeDraw.draw(weights, random));
        }
    }

    @Test
    void theDistributionFollowsTheWeights() {
        // 70 / 26 / 4, which is roughly the intended common / uncommon / rare split.
        final List<Integer> weights = List.of(70, 26, 4);
        final Random random = new Random(20260901L);
        final int[] hits = new int[3];
        final int rolls = 200_000;
        for (int i = 0; i < rolls; i++) {
            hits[PrizeDraw.draw(weights, random)]++;
        }

        assertTrue(Math.abs(hits[0] / (double) rolls - 0.70) < 0.01, "common band: " + hits[0]);
        assertTrue(Math.abs(hits[1] / (double) rolls - 0.26) < 0.01, "uncommon band: " + hits[1]);
        assertTrue(Math.abs(hits[2] / (double) rolls - 0.04) < 0.01, "rare band: " + hits[2]);
    }

    @Test
    void aPoolWithNothingInItIsRefusedRatherThanSpun() {
        assertThrows(IllegalArgumentException.class, () -> PrizeDraw.draw(List.of(), new Random()));
        assertThrows(IllegalArgumentException.class, () -> PrizeDraw.draw(null, new Random()));
        assertThrows(IllegalArgumentException.class,
                () -> PrizeDraw.draw(List.of(0, 0), new Random()));
    }

    /** One rule for the aura share and the extra spins, so there is one place to change it. */
    @Test
    void extraSpinsAreStaggeredByContributionShare() {
        final List<Integer> thresholds = List.of(2, 10, 25);

        assertEquals(0, PrizeDraw.extraSpinsFor(thresholds, 1.9));
        assertEquals(1, PrizeDraw.extraSpinsFor(thresholds, 2.0), "exactly at the threshold counts");
        assertEquals(1, PrizeDraw.extraSpinsFor(thresholds, 9.9));
        assertEquals(2, PrizeDraw.extraSpinsFor(thresholds, 10.0));
        assertEquals(3, PrizeDraw.extraSpinsFor(thresholds, 25.0));
        assertEquals(3, PrizeDraw.extraSpinsFor(thresholds, 100.0), "there is no fourth spin");
    }

    @Test
    void noThresholdsMeansNoExtraSpins() {
        assertEquals(0, PrizeDraw.extraSpinsFor(List.of(), 50.0));
        assertEquals(0, PrizeDraw.extraSpinsFor(null, 50.0));
    }
}
