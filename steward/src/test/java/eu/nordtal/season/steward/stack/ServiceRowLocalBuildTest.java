package eu.nordtal.season.steward.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.internalapi.agent.ImageResult;
import eu.nordtal.season.steward.WireJson;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A row says what its service runs that was built on the host, with the jars by name, and nothing otherwise. */
class ServiceRowLocalBuildTest {

    private static final AgentWire.Topology TOPOLOGY =
            new AgentWire.Topology(List.of(new AgentWire.Service("smp", null, true, false)), List.of());

    @Test
    void aJarBuiltOutsideAReleaseIsNamedOnTheRow() {
        final ImageResult images = ImageResult.of(Map.of("smp", ImageResult.State.UP_TO_DATE))
                .withLocalBuilds(Map.of("smp", new ImageResult.LocalBuild(null, List.of("smp-0.17.0.jar"))));

        final JsonObject row = row(images);

        assertEquals(
                "smp-0.17.0.jar",
                row.getAsJsonObject("localBuild").getAsJsonArray("jars").get(0).getAsString());
        assertFalse(row.getAsJsonObject("localBuild").has("image"), "the image is the release's");
    }

    @Test
    void aServiceOnTheReleaseCarriesNoLocalBuild() {
        assertFalse(
                row(ImageResult.of(Map.of("smp", ImageResult.State.UP_TO_DATE))).has("localBuild"));
    }

    private static JsonObject row(final ImageResult images) {
        final AgentWire.Container container = new AgentWire.Container(
                "smp", "id-smp", null, null, "running", null, null, "2026-10-06T01:00:00Z", null, null, null, null);
        return WireJson.gson()
                .toJsonTree(
                        ServiceRows.describe(container, images, ServicesApi.Online.NONE, Map.of(), Set.of(), TOPOLOGY))
                .getAsJsonObject();
    }
}
