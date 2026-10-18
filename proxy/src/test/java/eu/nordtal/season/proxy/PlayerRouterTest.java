package eu.nordtal.season.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** A move onto {@code smp} after a phase change passes the waiting room, whose queue lets the player on. */
class PlayerRouterTest {

    private final PhaseServers servers = new PhaseServers("limbo", "limbo-standby", "hunger-games", "smp");

    @Test
    void aMoveOntoSmpGoesThroughTheWaitingRoom() {
        assertEquals("limbo", PlayerRouter.viaWaitingRoom("smp", servers, Set.of("limbo", "smp")));
    }

    @Test
    void withoutAWaitingRoomTheMoveGoesStraightThere() {
        assertEquals("smp", PlayerRouter.viaWaitingRoom("smp", servers, Set.of("smp")));
    }

    @Test
    void otherServersAreNotQueued() {
        assertEquals(
                "hunger-games", PlayerRouter.viaWaitingRoom("hunger-games", servers, Set.of("limbo", "hunger-games")));
    }
}
