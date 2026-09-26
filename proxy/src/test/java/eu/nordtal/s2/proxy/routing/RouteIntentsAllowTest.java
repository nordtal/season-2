package eu.nordtal.s2.proxy.routing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.proxy.PhaseServers;
import org.junit.jupiter.api.Test;

/**
 * A player being evacuated into the standby waiting room is not refused.
 *
 * This is the assertion that was missing when the standby was added, and its absence was not
 * academic: {@code Evacuation} moves players with a bare {@code fireAndForget} and registers no
 * intent, because the destination is a waiting room and a waiting room has never needed one. With
 * the single name in this class that stopped being true for {@code limbo-standby} - so the one run
 * the whole feature exists for, the one that stops the limbo itself, would have had every non-admin
 * left standing on a backend thirty seconds from being stopped.
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
        // /server hunger-games during the SMP phase - the thing this class was written for.
        assertFalse(RouteIntents.allows(SERVERS, "hunger-games", "smp"));
        assertFalse(RouteIntents.allows(SERVERS, "smp", null));
    }
}
