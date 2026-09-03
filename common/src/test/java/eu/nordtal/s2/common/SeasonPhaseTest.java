package eu.nordtal.s2.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SeasonPhase} sits on the login path the same way {@code Locales} does, and the same rule
 * applies: every case has to end in a phase rather than an exception, and the one it ends in when
 * something is wrong has to be the phase that lets nobody in.
 */
class SeasonPhaseTest {

    @Test
    void theOrderingIsTheNetworksRoutingOrder() {
        // The enum's own javadoc calls this the routing order, and docs/season-phases.md draws the
        // season as PRE_LAUNCH -> PRE_EVENT -> START_EVENT -> SMP with MAINTENANCE as the
        // interruption of any of them. Something later will switch on ordinals or render this list;
        // pin it. PRE_LAUNCH was added at the FRONT on 2026-09-03, which is where the season starts.
        assertEquals(
                List.of(SeasonPhase.PRE_LAUNCH, SeasonPhase.PRE_EVENT, SeasonPhase.START_EVENT,
                        SeasonPhase.SMP, SeasonPhase.MAINTENANCE),
                List.of(SeasonPhase.values()));
    }

    @Test
    void parsesTheValuesTheColumnActuallyStores() {
        assertEquals(SeasonPhase.PRE_LAUNCH, SeasonPhase.fromDatabase("PRE_LAUNCH"));
        assertEquals(SeasonPhase.PRE_EVENT, SeasonPhase.fromDatabase("PRE_EVENT"));
        assertEquals(SeasonPhase.START_EVENT, SeasonPhase.fromDatabase("START_EVENT"));
        assertEquals(SeasonPhase.SMP, SeasonPhase.fromDatabase("SMP"));
        assertEquals(SeasonPhase.MAINTENANCE, SeasonPhase.fromDatabase("MAINTENANCE"));
    }

    @Test
    void anythingUnreadableIsMaintenanceRatherThanAnException() {
        assertEquals(SeasonPhase.MAINTENANCE, SeasonPhase.fromDatabase(null));
        assertEquals(SeasonPhase.MAINTENANCE, SeasonPhase.fromDatabase(""));
        assertEquals(SeasonPhase.MAINTENANCE, SeasonPhase.fromDatabase("RESOURCE_PACK_INSTALL"),
                "the retired season-1 value must not resolve to anything permissive");
        assertEquals(SeasonPhase.MAINTENANCE, SeasonPhase.fromDatabase("smp "));
    }
}
