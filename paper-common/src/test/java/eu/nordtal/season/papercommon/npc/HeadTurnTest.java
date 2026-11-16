package eu.nordtal.season.papercommon.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The head eases towards a player, keeps its pitch within bounds and turns the body only part of the way. */
class HeadTurnTest {

    @Test
    void theHeadEasesTowardsThePlayer() {
        final HeadTurn.Pose pose = new HeadTurn(0).tick(new HeadTurn.Offset(-1, 0, 0));
        assertEquals(90 * HeadTurn.EASE, pose.headYaw(), 1e-4);
    }

    @Test
    void thePitchStaysWithinItsBound() {
        final HeadTurn head = new HeadTurn(0);
        HeadTurn.Pose pose = head.tick(new HeadTurn.Offset(0, -10, 0.1));
        for (int tick = 0; tick < 100; tick++) {
            pose = head.tick(new HeadTurn.Offset(0, -10, 0.1));
        }
        assertEquals(HeadTurn.MOST_PITCH, pose.headPitch(), 1e-3);
    }

    @Test
    void theBodyFollowsAtMostPartOfTheWay() {
        final HeadTurn head = new HeadTurn(0);
        HeadTurn.Pose pose = head.tick(new HeadTurn.Offset(-1, 0, -0.2));
        for (int tick = 0; tick < 100; tick++) {
            pose = head.tick(new HeadTurn.Offset(-1, 0, -0.2));
        }
        assertTrue(pose.headYaw() > 90);
        assertEquals(HeadTurn.MOST_BODY, pose.bodyYaw(), 1e-3);
    }

    @Test
    void withoutAPlayerTheHeadTurnsHome() {
        final HeadTurn head = new HeadTurn(30);
        head.tick(new HeadTurn.Offset(1, 0, 0));
        HeadTurn.Pose pose = head.tick(null);
        for (int tick = 0; tick < 100; tick++) {
            pose = head.tick(null);
        }
        assertEquals(30, pose.headYaw(), 1e-3);
        assertEquals(0, pose.headPitch(), 1e-3);
    }
}
