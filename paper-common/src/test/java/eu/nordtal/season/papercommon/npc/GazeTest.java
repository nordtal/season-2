package eu.nordtal.season.papercommon.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Yaw 0 faces south, 90 west; pitch grows downwards; an angle away counts both ways. */
class GazeTest {

    @Test
    void theYawFollowsMinecraftsCompass() {
        assertEquals(0.0, Gaze.yawTo(0, 1), 1e-9);
        assertEquals(90.0, Gaze.yawTo(-1, 0), 1e-9);
        assertEquals(-90.0, Gaze.yawTo(1, 0), 1e-9);
    }

    @Test
    void lookingUpIsANegativePitch() {
        assertEquals(-45.0, Gaze.pitchTo(0, 1, 1), 1e-9);
        assertEquals(45.0, Gaze.pitchTo(1, -1, 0), 1e-9);
    }

    @Test
    void wrappingKeepsTheAngleWithinHalfATurn() {
        assertEquals(-170.0, Gaze.wrap(190), 1e-9);
        assertEquals(170.0, Gaze.wrap(-190), 1e-9);
        assertEquals(-180.0, Gaze.wrap(180), 1e-9);
        assertEquals(10.0, Gaze.wrap(730), 1e-9);
    }

    @Test
    void wherePlayerStandsCountsTheStepsBeyondTheStartAndTheTurnAway() {
        final Where facing = Gaze.where(2.0, 0, 1, 2.5, 0);
        assertEquals(0.5, facing.beyond(), 1e-9);
        assertEquals(0.0, facing.angle(), 1e-9);
        assertEquals(150.0, Gaze.where(2.0, 0, 1, 2.0, -150).angle(), 1e-9);
        assertEquals(150.0, Gaze.where(2.0, 0, 1, 2.0, 150).angle(), 1e-9);
    }
}
