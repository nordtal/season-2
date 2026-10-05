package eu.nordtal.season.stewardagent.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.nordtal.season.internalapi.agent.AgentWire;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Each plugin row names the release that installed its jar, matched by file name, and only where one was noted. */
class PluginReleaseRowTest {

    private static AgentWire.Plugin row(final String fileName) {
        return new AgentWire.Plugin(
                fileName,
                AgentWire.PluginGroup.PREINSTALLED,
                null,
                true,
                false,
                null,
                fileName.isEmpty() ? null : fileName,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void aNotedJarCarriesItsReleaseAndOthersCarryNone() {
        final List<AgentWire.Plugin> noted = PluginsApi.noteReleases(
                List.of(row("smp-0.11.0.jar"), row("worldedit-bukkit-7.3.jar"), row("smp-0.10.3.jar"), row("")),
                Map.of("smp-0.11.0.jar", "0.11.0", "voicechat-bukkit-2.6.1.jar", "0.10.3"));

        assertEquals("0.11.0", noted.get(0).release());
        assertNull(noted.get(1).release());
        assertNull(noted.get(2).release(), "a file the note does not name is not its artefact's release");
        assertNull(noted.get(3).release());
    }
}
