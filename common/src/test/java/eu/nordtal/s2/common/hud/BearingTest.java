package eu.nordtal.s2.common.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BearingTest {

    @Test
    void targetDirectlyAheadIsIndexZero() {
        // Player at origin, facing yaw 0 (south, +Z). Target due south.
        assertEquals(0, Bearing.arrowIndex(0, 0, 0, 0, 10));
    }

    @Test
    void targetBehindIsIndexEight() {
        // Facing south, target due north (behind) -> 180 degrees -> index 8.
        assertEquals(8, Bearing.arrowIndex(0, 0, 0, 0, -10));
    }

    @Test
    void targetToTheRightOfFacingIsAQuarterTurn() {
        // Facing south (yaw 0), target due west (-X) is 90 degrees clockwise from south in
        // Minecraft's yaw convention -> index 4 (90 / 22.5).
        assertEquals(4, Bearing.arrowIndex(0, 0, 0, -10, 0));
    }

    @Test
    void playerYawIsSubtractedFromTheBearing() {
        // Facing east (yaw -90 in this convention... verified relatively): rotate the player's own
        // facing by 90 and the target by the same amount, the arrow index must be unchanged.
        final int base = Bearing.arrowIndex(0, 0, 0, 0, 10);
        final int rotated = Bearing.arrowIndex(0, 0, 90, -10, 0);
        assertEquals(base, rotated);
    }

    @Test
    void sameLocationIsIndexZero() {
        assertEquals(0, Bearing.arrowIndex(5, 5, 123, 5, 5));
    }

    @Test
    void resultIsAlwaysInRange() {
        for (int yaw = -360; yaw <= 360; yaw += 15) {
            for (int angle = 0; angle < 360; angle += 15) {
                final double x = Math.cos(Math.toRadians(angle));
                final double z = Math.sin(Math.toRadians(angle));
                final int index = Bearing.arrowIndex(0, 0, yaw, x, z);
                org.junit.jupiter.api.Assertions.assertTrue(index >= 0 && index < 16);
            }
        }
    }
}
