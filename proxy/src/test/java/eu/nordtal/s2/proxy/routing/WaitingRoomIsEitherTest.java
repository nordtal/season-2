package eu.nordtal.s2.proxy.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.SeasonPhase;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "Is this player waiting" has two right answers now (season-2-ops/120).
 *
 * <p>The ticket's own warning is what this file is for: <em>half built is worse here than not
 * built.</em> Moving somebody to {@code limbo-standby} is easy; the hard part is that four separate
 * places decide "is this player in the waiting room" by comparing against the <b>one</b> limbo, and
 * a player on the standby is, to every one of them, somebody on an unrelated backend. They would
 * never be released.</p>
 */
class WaitingRoomIsEitherTest {

    private static final PhaseServers SERVERS = new PhaseServers("limbo", "limbo-standby", "hunger-games", "smp");

    @Test
    @DisplayName("both rooms are the waiting room, and nothing else is")
    void bothRoomsAreTheWaitingRoom() {
        assertTrue(SERVERS.isWaitingRoom("limbo"));
        assertTrue(SERVERS.isWaitingRoom("limbo-standby"));

        assertFalse(SERVERS.isWaitingRoom("smp"));
        assertFalse(SERVERS.isWaitingRoom("hunger-games"));
        // Not a curiosity: getCurrentServer() is an Optional at every call site that asks this.
        assertFalse(SERVERS.isWaitingRoom(null));
        // And not a prefix match - a server called "limbo-standby-2" is not this one.
        assertFalse(SERVERS.isWaitingRoom("limbo-standby-2"));
    }

    @Test
    @DisplayName("a player joining while the limbo is down lands in the standby, not on a refusal")
    void aJoinDuringTheSwapLandsInTheStandby() {
        final PhaseRouting routing = new PhaseRouting(SERVERS);

        // What a proxy has registered mid-swap: the limbo is stopped, the standby is up.
        final Set<String> midSwap = Set.of("limbo-standby", "smp", "hunger-games");

        assertEquals(
                "limbo-standby",
                routing.decideInitial(SeasonPhase.MAINTENANCE, false, midSwap).server(),
                "a player joining while the waiting room is being replaced was refused outright");
        assertEquals(
                "limbo-standby",
                routing.decideInitial(SeasonPhase.SMP, false, midSwap).server());
    }

    @Test
    @DisplayName("with both rooms up the live one wins, so arrivals are not split across two")
    void theLiveRoomWinsWhenBothAreUp() {
        final PhaseRouting routing = new PhaseRouting(SERVERS);
        final Set<String> both = Set.of("limbo", "limbo-standby", "smp", "hunger-games");

        assertEquals(
                "limbo", routing.decideInitial(SeasonPhase.SMP, false, both).server());
    }
}
