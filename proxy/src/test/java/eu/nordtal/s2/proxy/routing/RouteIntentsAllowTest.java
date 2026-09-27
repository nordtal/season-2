package eu.nordtal.s2.proxy.routing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.proxy.PhaseServers;
import org.junit.jupiter.api.Test;

/**
 * A player being evacuated into the standby waiting room is not refused.
 *
 * {@code Evacuation} registers no intent, since a waiting room never needs one.
 */
class RouteIntentsAllowTest {

    private static final PhaseServers SERVERS = new PhaseServers("limbo", "limbo-standby", "hunger-games", "smp");

    @Test
    void bothWaitingRoomsNeedNoIntent() {
        assertTrue(RouteIntents.allows(SERVERS, "limbo", null));
        assertTrue(RouteIntents.allows(SERVERS, "limbo-standby", null));
    }

    @Test
    void aBackendStillNeedsItsIntent() {
        assertTrue(RouteIntents.allows(SERVERS, "smp", "smp"));
        // /server hunger-games during the SMP phase.
        assertFalse(RouteIntents.allows(SERVERS, "hunger-games", "smp"));
        assertFalse(RouteIntents.allows(SERVERS, "smp", null));
    }
}
