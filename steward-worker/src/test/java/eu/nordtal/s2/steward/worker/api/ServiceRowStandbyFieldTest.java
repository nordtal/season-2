package eu.nordtal.s2.steward.worker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.steward.worker.plan.Topology;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The standby marker keeps a stopped standby out of the fault count.
 *
 * Docker cannot tell a stopped standby from a crashed backend; only {@link Topology#standbyNames()} knows.
 */
class ServiceRowStandbyFieldTest {

    @Test
    void everyStandbyTopologyKnowsIsMarked() {
        assertFalse(Topology.standbyNames().isEmpty(), "a rule about an empty list proves nothing");

        for (final String standby : Topology.standbyNames()) {
            assertEquals(
                    true, row(standby).get("standby"), standby + " is off on purpose and must not read as a fault");
        }
    }

    @Test
    void anOrdinaryServiceCarriesNoKeyAtAllAbsentNeverFalse() {
        final Map<String, Object> row = row("smp");

        assertFalse(
                row.containsKey("standby"),
                "the frontend reads `standby === true`; a `false` here would be a second spelling");
        assertTrue(row.isEmpty(), "nothing else is written either - it adds one field or none");
    }

    @Test
    void theLiveServiceAStandbyBelongsToIsNotMarked() {
        // A substring match would let a dead proxy pass as its standby.
        for (final String standby : Topology.standbyNames()) {
            final String live = standby.substring(0, standby.lastIndexOf('-'));
            assertFalse(row(live).containsKey("standby"), live + " being down is an outage, not a standby at rest");
        }
    }

    @Test
    void aNameThatMerelyLooksLikeOneIsNotOne() {
        assertFalse(
                row("postgres-standby").containsKey("standby"),
                "the answer comes from Topology, not from how the name ends");
        assertTrue(
                Topology.standbyNames().stream().noneMatch("postgres-standby"::equals),
                "if this ever becomes a real service, the test above is the one to change");
    }

    private static Map<String, Object> row(final String service) {
        final Map<String, Object> row = new LinkedHashMap<>();
        ServiceRows.putStandby(row, service);
        return row;
    }
}
