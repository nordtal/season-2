package eu.nordtal.season.papercommon.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Objects;
import org.junit.jupiter.api.Test;

/** The view eases back to the NPC while the mouse rests, and the player's own turn pauses it. */
class CameraEaseTest {

    @Test
    void aRestingMouseTurnsTheViewAFractionOfTheWayEachTick() {
        final CameraEase ease = new CameraEase(0, 0);
        final CameraEase.Turn turn = Objects.requireNonNull(ease.tick(0, 0, 20, 0));
        assertEquals(20 * CameraEase.EASE, turn.yaw(), 1e-9);
        assertEquals(0.0, turn.pitch(), 1e-9);
    }

    @Test
    void noTurnIsLargerThanTheMostStep() {
        final CameraEase.Turn turn = Objects.requireNonNull(new CameraEase(0, 0).tick(0, 0, 170, 0));
        assertEquals(CameraEase.MAX_STEP, turn.yaw(), 1e-9);
    }

    @Test
    void theTurnItSentIsNotTakenForTheMouse() {
        final CameraEase ease = new CameraEase(0, 0);
        final CameraEase.Turn first = Objects.requireNonNull(ease.tick(0, 0, 20, 0));
        assertNotNull(ease.tick(first.yaw(), 0, 20, 0), "the own turn arrived, and the ease goes on");
    }

    @Test
    void theMouseMovingPausesTheEaseUntilItRestsAgain() {
        final CameraEase ease = new CameraEase(0, 0);
        assertNull(ease.tick(-30, 0, 20, 0));
        for (int tick = 1; tick < CameraEase.REST_TICKS; tick++) {
            assertNull(ease.tick(-30, 0, 20, 0), "tick " + tick);
        }
        assertNotNull(ease.tick(-30, 0, 20, 0));
    }

    @Test
    void aViewOnTheNpcAlreadyTurnsNot() {
        assertNull(new CameraEase(20, 5).tick(20, 5, 20.1, 5.1));
    }
}
