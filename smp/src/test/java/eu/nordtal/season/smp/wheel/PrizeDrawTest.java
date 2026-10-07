package eu.nordtal.season.smp.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The wheel's weighted draw, asserted with a seeded {@link Random}. */
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
        assertThrows(IllegalArgumentException.class, () -> PrizeDraw.draw(List.of(0, 0), new Random()));
    }
}
