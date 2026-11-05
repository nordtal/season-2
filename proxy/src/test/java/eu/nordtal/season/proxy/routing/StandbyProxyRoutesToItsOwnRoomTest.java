package eu.nordtal.season.proxy.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.proxy.NetworkServers;
import eu.nordtal.season.proxy.ProxyRole;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A standby proxy puts arrivals in the standby waiting room.
 *
 * Arrivals just left the live proxy, some from {@code limbo}, and must not rejoin a backend they were on.
 */
class StandbyProxyRoutesToItsOwnRoomTest {

    private static final NetworkServers SERVERS = new NetworkServers("limbo", "limbo-standby", "hunger-games", "smp");
    private static final Set<String> BOTH = Set.of("limbo", "limbo-standby", "hunger-games", "smp");

    @Test
    void theStandbyPrefersItsOwnRoom() {
        assertEquals("limbo-standby", SeasonRouting.waitingRoomAmong(BOTH, SERVERS, ProxyRole.STANDBY));
    }

    @Test
    void theLiveProxyPrefersTheLiveRoom() {
        assertEquals("limbo", SeasonRouting.waitingRoomAmong(BOTH, SERVERS, ProxyRole.LIVE));
    }

    @Test
    void eachFallsBackToTheOther() {
        assertEquals(
                "limbo-standby",
                SeasonRouting.waitingRoomAmong(Set.of("limbo-standby", "smp"), SERVERS, ProxyRole.LIVE));
        assertEquals("limbo", SeasonRouting.waitingRoomAmong(Set.of("limbo", "smp"), SERVERS, ProxyRole.STANDBY));
    }

    @Test
    void noRoomIsStillNoRoom() {
        assertEquals(null, SeasonRouting.waitingRoomAmong(Set.of("smp"), SERVERS, ProxyRole.LIVE));
        assertEquals(null, SeasonRouting.waitingRoomAmong(Set.of("smp"), SERVERS, ProxyRole.STANDBY));
    }

    @Test
    void everyLoginLandsInTheStandbyRoom() {
        final SeasonRouting standby = new SeasonRouting(SERVERS, ProxyRole.STANDBY);
        for (final SeasonState season : new SeasonState[] {
            SeasonState.UNREAD, new SeasonState(null, false, SeasonMode.SMP), SeasonState.UNREAD.withMaintenance(false)
        }) {
            assertEquals(
                    "limbo-standby", standby.decideInitial(season, false, BOTH).server(), season.toString());
            // Admins too: sending a parked admin onward would strand them on a proxy about to stop.
            assertEquals(
                    "limbo-standby", standby.decideInitial(season, true, BOTH).server(), season.toString());
        }
    }
}
