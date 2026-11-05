package eu.nordtal.season.proxy.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.proxy.NetworkServers;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Either limbo counts as the waiting room, so a player on the standby is still released. */
class WaitingRoomIsEitherTest {

    private static final NetworkServers SERVERS = new NetworkServers("limbo", "limbo-standby", "hunger-games", "smp");

    @Test
    void bothRoomsAreTheWaitingRoom() {
        assertTrue(SERVERS.isWaitingRoom("limbo"));
        assertTrue(SERVERS.isWaitingRoom("limbo-standby"));

        assertFalse(SERVERS.isWaitingRoom("smp"));
        assertFalse(SERVERS.isWaitingRoom("hunger-games"));
        // getCurrentServer() is an Optional at every call site that asks this.
        assertFalse(SERVERS.isWaitingRoom(null));
        // Not a prefix match: "limbo-standby-2" is another server.
        assertFalse(SERVERS.isWaitingRoom("limbo-standby-2"));
    }

    @Test
    void aJoinDuringTheSwapLandsInTheStandby() {
        final SeasonRouting routing = new SeasonRouting(SERVERS);

        // What a proxy has registered mid-swap: the limbo is stopped, the standby is up.
        final Set<String> midSwap = Set.of("limbo-standby", "smp", "hunger-games");

        assertEquals(
                "limbo-standby",
                routing.decideInitial(SeasonState.UNREAD, false, midSwap).server(),
                "a player joining while the waiting room is being replaced was refused outright");
        assertEquals(
                "limbo-standby",
                routing.decideInitial(SeasonState.UNREAD.withMaintenance(false), false, midSwap)
                        .server());
    }

    @Test
    void theLiveRoomWinsWhenBothAreUp() {
        final SeasonRouting routing = new SeasonRouting(SERVERS);
        final Set<String> both = Set.of("limbo", "limbo-standby", "smp", "hunger-games");

        assertEquals(
                "limbo",
                routing.decideInitial(SeasonState.UNREAD.withMaintenance(false), false, both)
                        .server());
    }
}
