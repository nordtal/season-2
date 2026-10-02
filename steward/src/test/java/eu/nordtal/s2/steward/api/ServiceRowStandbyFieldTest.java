package eu.nordtal.s2.steward.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.internalapi.agent.AgentWire;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The standby marker keeps a stopped standby out of the fault count.
 *
 * Docker cannot tell a stopped standby from a crashed backend; only the topology the agent serves knows.
 */
class ServiceRowStandbyFieldTest {

    /** What the agent serves, with the label {@code eu.nordtal.standby-of} on the two standbys. */
    private static final AgentWire.Topology TOPOLOGY = new AgentWire.Topology(
            List.of(
                    new AgentWire.Service("proxy", null, true, false),
                    new AgentWire.Service("proxy-standby", null, false, false, null, "proxy", null),
                    new AgentWire.Service("limbo", null, true, false),
                    new AgentWire.Service("limbo-standby", null, false, false, null, "limbo", null),
                    new AgentWire.Service("smp", null, true, true),
                    new AgentWire.Service("postgres", null, false, false)),
            List.of());

    @Test
    void everyStandbyTheTopologyKnowsIsMarked() {
        assertEquals(List.of("proxy-standby", "limbo-standby"), TOPOLOGY.standbys());

        for (final String standby : TOPOLOGY.standbys()) {
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
        for (final String live : List.of("proxy", "limbo")) {
            assertFalse(row(live).containsKey("standby"), live + " being down is an outage, not a standby at rest");
        }
    }

    @Test
    void aNameThatMerelyLooksLikeOneIsNotOne() {
        assertFalse(
                row("postgres-standby").containsKey("standby"),
                "the answer comes from the label, not from how the name ends");
    }

    private static Map<String, Object> row(final String service) {
        final Map<String, Object> row = new LinkedHashMap<>();
        ServiceRows.putStandby(row, service, TOPOLOGY);
        return row;
    }
}
