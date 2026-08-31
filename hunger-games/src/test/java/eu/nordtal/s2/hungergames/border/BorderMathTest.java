package eu.nordtal.s2.hungergames.border;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BorderMathTest {

    @Test
    void deathStepMatchesTheDocumentedExampleForTwentyPlayers() {
        // "With 20 players that is roughly 13 blocks of diameter per death" - docs/hunger-games.md
        final double step = BorderMath.deathStep(250.0, 1.0, 20);
        assertEquals(13.105263157894736, step, 1e-9);
    }

    @Test
    void deathStepRefusesFewerThanTwoParticipants() {
        assertThrows(IllegalArgumentException.class, () -> BorderMath.deathStep(250.0, 1.0, 1));
    }

    @Test
    void deathStepRefusesAnInvertedRange() {
        assertThrows(IllegalArgumentException.class, () -> BorderMath.deathStep(1.0, 250.0, 10));
    }

    @Test
    void nextShrinkTargetExtendsRatherThanRestarts() {
        // "A death during a shrink extends it rather than restarting it: the target moves further
        // in and the wall keeps its speed."
        final double target = BorderMath.nextShrinkTarget(200.0, 13.0, 1.0);
        assertEquals(187.0, target, 1e-9);
    }

    @Test
    void nextShrinkTargetNeverPassesTheFloor() {
        final double target = BorderMath.nextShrinkTarget(5.0, 13.0, 1.0);
        assertEquals(1.0, target, 1e-9);
    }

    @Test
    void shrinkDurationIsDeltaOverSpeed() {
        final long millis = BorderMath.shrinkDurationMillis(250.0, 200.0, 6.0);
        assertEquals(50.0 / 6.0 * 1000.0, millis, 1.0);
    }

    @Test
    void shrinkDurationRefusesNonPositiveSpeed() {
        assertThrows(IllegalArgumentException.class,
                () -> BorderMath.shrinkDurationMillis(250.0, 200.0, 0.0));
    }

    @Test
    void passiveShrinkDurationIsDeltaOverHourlyRate() {
        final long millis = BorderMath.passiveShrinkDurationMillis(250.0, 1.0, 15.0);
        // 249 blocks at 15 blocks/hour = 16.6 hours
        assertEquals(249.0 / 15.0 * 3_600_000.0, millis, 1.0);
    }
}
