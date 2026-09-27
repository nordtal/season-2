package eu.nordtal.s2.steward.worker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.steward.worker.plan.Topology;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The marker that keeps a stopped standby out of the fault count.
 *
 * Both standbys are stopped for all but a minute of the season, so without this marker the start page would report
 * two faults on a healthy stack every single day - which is how a fault counter stops being read.
 *
 * This is asserted on this side of the wire on purpose. The frontend cannot decide it: to Docker a stopped standby
 * and a crashed backend are the same container state, and a name match in the browser would silently exempt any
 * future service somebody happens to name {@code something-standby}. {@link Topology#standbyNames()} is the only
 * thing that knows.
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
        // proxy/proxy-standby and limbo/limbo-standby: a substring match here would let a dead proxy go quiet.
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
