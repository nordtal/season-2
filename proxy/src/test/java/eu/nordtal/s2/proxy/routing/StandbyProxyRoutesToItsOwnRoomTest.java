package eu.nordtal.s2.proxy.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.proxy.PhaseServers;
import eu.nordtal.s2.proxy.ProxyRole;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A standby proxy puts arrivals in the standby waiting room, and that is the whole safety argument for running one.
 *
 * A proxy swap does not touch the backends: {@code limbo} is up and registered throughout, so a
 * standby using the live rule would send every arrival there. But the arrivals are the players who
 * just left the live proxy, and some of them were standing in {@code limbo} when they did - the
 * same UUID leaving and rejoining one Paper server inside a second, which is the "You are already
 * logged in" the ticket names as the thing to reproduce before building anything. The other room
 * means nobody rejoins a backend they were on, so the unmeasured question cannot reach the code.
 */
class StandbyProxyRoutesToItsOwnRoomTest {

    private static final PhaseServers SERVERS = new PhaseServers("limbo", "limbo-standby", "hunger-games", "smp");
    private static final Set<String> BOTH = Set.of("limbo", "limbo-standby", "hunger-games", "smp");

    @Test
    void theStandbyPrefersItsOwnRoom() {
        assertEquals("limbo-standby", PhaseRouting.waitingRoomAmong(BOTH, SERVERS, ProxyRole.STANDBY));
    }

    @Test
    void theLiveProxyPrefersTheLiveRoom() {
        assertEquals("limbo", PhaseRouting.waitingRoomAmong(BOTH, SERVERS, ProxyRole.LIVE));
    }

    @Test
    void eachFallsBackToTheOther() {
        assertEquals(
                "limbo-standby",
                PhaseRouting.waitingRoomAmong(Set.of("limbo-standby", "smp"), SERVERS, ProxyRole.LIVE));
        assertEquals("limbo", PhaseRouting.waitingRoomAmong(Set.of("limbo", "smp"), SERVERS, ProxyRole.STANDBY));
    }

    @Test
    void noRoomIsStillNoRoom() {
        assertEquals(null, PhaseRouting.waitingRoomAmong(Set.of("smp"), SERVERS, ProxyRole.LIVE));
        assertEquals(null, PhaseRouting.waitingRoomAmong(Set.of("smp"), SERVERS, ProxyRole.STANDBY));
    }

    @Test
    void everyLoginLandsInTheStandbyRoom() {
        final PhaseRouting standby = new PhaseRouting(SERVERS, ProxyRole.STANDBY);
        for (final SeasonPhase phase : SeasonPhase.values()) {
            assertEquals(
                    "limbo-standby", standby.decideInitial(phase, false, BOTH).server(), phase.toString());
            // Admins too: sending a parked admin onward would strand them on the proxy about to be stopped.
            assertEquals(
                    "limbo-standby", standby.decideInitial(phase, true, BOTH).server(), phase.toString());
        }
    }
}
