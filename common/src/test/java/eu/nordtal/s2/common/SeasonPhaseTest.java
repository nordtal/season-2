package eu.nordtal.s2.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Checks that every {@link SeasonPhase} case ends in a phase, never more permissive than the real one. */
class SeasonPhaseTest {

    @Test
    void theOrderingIsTheNetworksRoutingOrder() {
        // PRE_LAUNCH, PRE_EVENT, START_EVENT, SMP, then MAINTENANCE; pinned in case something uses ordinals.
        assertEquals(
                List.of(
                        SeasonPhase.PRE_LAUNCH,
                        SeasonPhase.PRE_EVENT,
                        SeasonPhase.START_EVENT,
                        SeasonPhase.SMP,
                        SeasonPhase.MAINTENANCE),
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
        assertEquals(
                SeasonPhase.MAINTENANCE,
                SeasonPhase.fromDatabase("RESOURCE_PACK_INSTALL"),
                "the retired season-1 value must not resolve to anything permissive");
        assertEquals(SeasonPhase.MAINTENANCE, SeasonPhase.fromDatabase("smp "));
    }
}
