package eu.nordtal.season.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.proxy.routing.RouteDecision;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A move onto {@code smp} after a change of the season passes the waiting room, whose queue lets the player on. */
class PlayerRouterTest {

    private final NetworkServers servers = new NetworkServers("limbo", "limbo-standby", "hunger-games", "smp");

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
        // The change of the season resolves to smp for everybody; those on it would otherwise queue again.
        assertEquals("smp", PlayerRouter.viaWaitingRoom("smp", "smp", servers, Set.of("limbo", "smp")));
    }

    @Test
    void otherServersAreNotQueued() {
        assertEquals(
                "hunger-games",
                PlayerRouter.viaWaitingRoom("hunger-games", "smp", servers, Set.of("limbo", "hunger-games")));
    }

    @Test
    void aRetryIntoTheWaitingRoomGoesOnWhileTheGateStillMovesThePlayer() {
        assertTrue(PlayerRouter.moveWanted("smp", "smp", toLimbo(), servers, Set.of("limbo", "smp")));
    }

    @Test
    void aRetryEndsOnceMaintenanceIsOffAndThePlayerStays() {
        assertFalse(PlayerRouter.moveWanted(
                "smp", "smp", new RouteDecision(RouteDecision.Action.CONNECT, "smp"), servers, Set.of("limbo", "smp")));
        assertFalse(PlayerRouter.moveWanted(
                "smp", "smp", new RouteDecision(RouteDecision.Action.STAY, null), servers, Set.of("limbo", "smp")));
    }

    @Test
    void aRetryGoesOnWhenTheGateNowRefusesThePlayer() {
        assertTrue(PlayerRouter.moveWanted(
                "smp",
                "smp",
                new RouteDecision(RouteDecision.Action.REFUSE_NO_ACCESS, null),
                servers,
                Set.of("limbo", "smp")));
    }

    @Test
    void aRetryEndsOnceThePlayerIsOnAnotherServer() {
        assertFalse(PlayerRouter.moveWanted("limbo", "smp", toLimbo(), servers, Set.of("limbo", "smp")));
    }

    private static RouteDecision toLimbo() {
        return new RouteDecision(RouteDecision.Action.CONNECT, "limbo");
    }
}
