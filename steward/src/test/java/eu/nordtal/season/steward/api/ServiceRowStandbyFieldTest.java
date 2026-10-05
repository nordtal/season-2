package eu.nordtal.season.steward.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import eu.nordtal.season.internalapi.agent.AgentWire;
import java.util.List;
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
            assertTrue(
                    row(standby).get("standby").getAsBoolean(),
                    standby + " is off on purpose and must not read as a fault");
        }
    }

    @Test
    void anOrdinaryServiceCarriesNoKeyAtAllAbsentNeverFalse() {
        assertFalse(
                row("smp").has("standby"),
                "the frontend reads `standby === true`; a `false` here would be a second spelling");
    }

    @Test
    void theLiveServiceAStandbyBelongsToIsNotMarked() {
        for (final String live : List.of("proxy", "limbo")) {
            assertFalse(row(live).has("standby"), live + " being down is an outage, not a standby at rest");
        }
    }

    /** The alert rule's verdict rides on the row, so a page colours a service as the alerts judge it. */
    @Test
    void aStoppedStandbyIsNotRedAndAStoppedLiveServiceIs() {
        for (final String standby : TOPOLOGY.standbys()) {
            assertFalse(row(standby).has("alert"), standby + " raises no alert, so no page may draw it red");
        }
        for (final String live : List.of("proxy", "limbo")) {
            assertEquals("down", row(live).get("alert").getAsString(), live + " stopped is what the alerts call down");
        }
    }

    @Test
    void aNameThatMerelyLooksLikeOneIsNotOne() {
        assertFalse(
                row("postgres-standby").has("standby"), "the answer comes from the label, not from how the name ends");
    }

    private static JsonObject row(final String service) {
        return ServiceRowOnlineFieldsTest.wireRow(service, ServicesApi.Online.NONE, TOPOLOGY);
    }
}
