package eu.nordtal.season.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** A move onto {@code smp} after a phase change passes the waiting room, whose queue lets the player on. */
class PlayerRouterTest {

    private final PhaseServers servers = new PhaseServers("limbo", "limbo-standby", "hunger-games", "smp");

    @Test
    void aMoveOntoSmpGoesThroughTheWaitingRoom() {
        assertEquals("limbo", PlayerRouter.viaWaitingRoom("smp", "hunger-games", servers, Set.of("limbo", "smp")));
    }

    @Test
    void withoutAWaitingRoomTheMoveGoesStraightThere() {
        assertEquals("smp", PlayerRouter.viaWaitingRoom("smp", "hunger-games", servers, Set.of("smp")));
    }

    @Test
    void aPlayerAlreadyOnSmpStaysThere() {
        // The phase change resolves to smp for everybody; those on it would otherwise queue again.
        assertEquals("smp", PlayerRouter.viaWaitingRoom("smp", "smp", servers, Set.of("limbo", "smp")));
    }

    @Test
    void otherServersAreNotQueued() {
        assertEquals(
                "hunger-games",
                PlayerRouter.viaWaitingRoom("hunger-games", "smp", servers, Set.of("limbo", "hunger-games")));
    }
}
