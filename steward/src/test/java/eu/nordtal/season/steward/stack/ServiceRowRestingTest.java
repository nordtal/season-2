package eu.nordtal.season.steward.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.steward.WireJson;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Tests that the server of the game mode that is not active rests instead of reading as down. */
class ServiceRowRestingTest {

    private static final AgentWire.Topology TOPOLOGY = new AgentWire.Topology(
            List.of(
                    new AgentWire.Service("smp", null, true, true),
                    new AgentWire.Service("hunger-games", null, true, true)),
            List.of());

    @Test
    void theInactiveModesStoppedServerRests() {
        final JsonObject row = row("smp", "smp");
        assertTrue(row.get("inactiveMode").getAsBoolean(), row.toString());
        assertFalse(row.has("alert"), "a resting server raises no alert, so no page may draw it red");
    }

    @Test
    void theActiveModesStoppedServerIsDown() {
        final JsonObject row = row("hunger-games", "smp");
        assertFalse(row.has("inactiveMode"), "the frontend reads `inactiveMode === true`");
        assertEquals("down", row.get("alert").getAsString());
    }

    @Test
    void withoutAModeNothingRests() {
        assertEquals("down", row("smp", null).get("alert").getAsString());
    }

    private static JsonObject row(final String service, final @Nullable String inactive) {
        final AgentWire.Container container = new AgentWire.Container(
                service, "id-" + service, null, null, "exited", null, null, null, null, null, null, null, 0);
        return WireJson.gson()
                .toJsonTree(ServiceRows.describe(
                        container,
                        new ImageResult(true, Map.of(), Set.of(), null, Map.of()),
                        ServicesApi.Online.NONE,
                        Map.of(),
                        Set.of(),
                        TOPOLOGY,
                        inactive))
                .getAsJsonObject();
    }
}
