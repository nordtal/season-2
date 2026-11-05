package eu.nordtal.season.proxy.routing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.proxy.NetworkServers;
import org.junit.jupiter.api.Test;

/**
 * A player being evacuated into the standby waiting room is not refused.
 *
 * {@code Evacuation} registers no intent, since a waiting room never needs one.
 */
class RouteIntentsAllowTest {

    private static final NetworkServers SERVERS = new NetworkServers("limbo", "limbo-standby", "hunger-games", "smp");

    @Test
    void bothWaitingRoomsNeedNoIntent() {
        assertTrue(RouteIntents.allows(SERVERS, "limbo", null));
        assertTrue(RouteIntents.allows(SERVERS, "limbo-standby", null));
    }

    @Test
    void aBackendStillNeedsItsIntent() {
        assertTrue(RouteIntents.allows(SERVERS, "smp", "smp"));
        // /server hunger-games while the SMP is the active mode.
        assertFalse(RouteIntents.allows(SERVERS, "hunger-games", "smp"));
        assertFalse(RouteIntents.allows(SERVERS, "smp", null));
    }
}
