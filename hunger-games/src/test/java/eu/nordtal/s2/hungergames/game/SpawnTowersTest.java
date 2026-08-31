package eu.nordtal.s2.hungergames.game;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpawnTowersTest {

    @Test
    void producesExactlyTheRequestedCount() {
        assertEquals(20, SpawnTowers.positions(20, 0, 0, 100).size());
    }

    @Test
    void everyPositionIsExactlyTheRadiusFromCentre() {
        final List<double[]> positions = SpawnTowers.positions(7, 10, -20, 50);
        for (final double[] position : positions) {
            final double dx = position[0] - 10;
            final double dz = position[1] - (-20);
            final double distance = Math.sqrt(dx * dx + dz * dz);
            assertEquals(50.0, distance, 1e-9);
        }
    }

    @Test
    void positionsAreEvenlySpaced() {
        final List<double[]> positions = SpawnTowers.positions(4, 0, 0, 10);
        // Four towers 90 degrees apart: first at (10, 0), then (0, 10), (-10, 0), (0, -10).
        assertEquals(10.0, positions.get(0)[0], 1e-9);
        assertEquals(0.0, positions.get(0)[1], 1e-9);
        assertEquals(0.0, positions.get(1)[0], 1e-9);
        assertEquals(10.0, positions.get(1)[1], 1e-9);
    }

    @Test
    void refusesNonPositiveCountOrRadius() {
        assertThrows(IllegalArgumentException.class, () -> SpawnTowers.positions(0, 0, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> SpawnTowers.positions(5, 0, 0, 0));
    }

    @Test
    void singleTowerSitsAtAngleZero() {
        final List<double[]> positions = SpawnTowers.positions(1, 0, 0, 25);
        assertEquals(25.0, positions.get(0)[0], 1e-9);
        assertEquals(0.0, positions.get(0)[1], 1e-9);
    }
}
