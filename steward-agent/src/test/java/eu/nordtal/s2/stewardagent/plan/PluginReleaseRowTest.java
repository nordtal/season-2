package eu.nordtal.s2.stewardagent.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Each plugin row names the release that installed its jar, matched by file name, and only where one was noted. */
class PluginReleaseRowTest {

    private static Map<String, Object> row(final String fileName) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", fileName);
        if (!fileName.isEmpty()) {
            row.put("fileName", fileName);
        }
        return row;
    }

    @Test
    void aNotedJarCarriesItsReleaseAndOthersCarryNone() {
        final Map<String, Object> smp = row("smp-0.11.0.jar");
        final Map<String, Object> byHand = row("worldedit-bukkit-7.3.jar");
        final Map<String, Object> older = row("smp-0.10.3.jar");
        final Map<String, Object> absent = row("");

        PluginsApi.noteReleases(
                List.of(smp, byHand, older, absent),
                Map.of("smp-0.11.0.jar", "0.11.0", "voicechat-bukkit-2.6.1.jar", "0.10.3"));

        assertEquals("0.11.0", smp.get("release"));
        assertFalse(byHand.containsKey("release"));
        assertFalse(older.containsKey("release"), "a file the note does not name is not its artefact's release");
        assertFalse(absent.containsKey("release"));
    }
}
