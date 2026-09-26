package eu.nordtal.s2.steward.worker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.nordtal.s2.common.config.EnvOverrideFile;
import eu.nordtal.s2.steward.worker.configfile.ConfigFiles;
import eu.nordtal.s2.steward.worker.configfile.ConfigLocation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one step between {@code ConfigFiles} and the browser, for steward/76 - exactly the shape of
 * gap {@link ConfigApiProtectedEntryTest} already found once for {@code protectedEntry}:
 * {@link ConfigApi#document} writes its answer key by key into a hand-built map, so
 * "the field exists on {@code ConfigEntry}" and "the field is sent" are two different facts, and a
 * suite that only exercises the record can stay green while the wire answer stays silent.
 *
 * <p>The third case below is the one steward/76's own report calls out by name: {@code false} must
 * never be sent as if it meant "we checked and this is fine" when the truth is "nobody checked" -
 * absent is the only honest way to say the second one, and a key sent as {@code null} would still
 * require the interface to test for it twice, which is exactly what steward/50 already rejected for
 * {@code choices} and {@code protectedEntry}.</p>
 */
class ConfigApiEnvOverrideTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("the JSON the browser is sent carries environmentOverridden: true for an overridden path")
    void theDocumentCarriesTrueForAnOverriddenPath() throws IOException {
        Files.writeString(directory.resolve("access.yml"), "languages:\n- tag: en\n");
        EnvOverrideFile.write(directory.resolve("access.yml"), List.of("languages"));

        assertEquals(
                Boolean.TRUE,
                entry("languages").get("environmentOverridden"),
                "api.ts declares ConfigEntry.environmentOverridden and configuration.tsx draws a"
                        + " warning badge from it - neither can do anything if the worker never"
                        + " sends it");
    }

    @Test
    @DisplayName("the JSON carries environmentOverridden: false for a path the service reported as not overridden")
    void theDocumentCarriesFalseForAReportedPath() throws IOException {
        Files.writeString(directory.resolve("access.yml"), "guild-id: '1'\nlanguages:\n- tag: en\n");
        EnvOverrideFile.write(directory.resolve("access.yml"), List.of("languages"));

        assertEquals(Boolean.FALSE, entry("guild-id").get("environmentOverridden"));
    }

    @Test
    @DisplayName("the JSON omits environmentOverridden entirely when no service ever reported")
    void theDocumentOmitsTheKeyWhenNoServiceEverReported() throws IOException {
        Files.writeString(directory.resolve("access.yml"), "guild-id: '1'\n");
        // Deliberately no EnvOverrideFile.write call at all - the untouched, pre-steward/76 case.

        assertFalse(
                entry("guild-id").containsKey("environmentOverridden"),
                "absent means \"this service never said\" - sending false here would be exactly the"
                        + " silent wrong answer steward/76 exists to prevent");
    }

    // --- fixtures ----------------------------------------------------------------------------

    /** The row for {@code path}, out of the document {@code /api/config/access.yml} would answer with. */
    private Map<String, Object> entry(final String path) throws IOException {
        final ConfigLocation location = ConfigFiles.discover(directory).stream()
                .filter(candidate -> candidate.name().equals("access.yml"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("access.yml was not discovered"));
        final Map<String, Object> document =
                ConfigApi.document(location, ConfigFiles.read(directory.resolve("access.yml")));
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> entries = (List<Map<String, Object>>) document.get("entries");
        return entries.stream()
                .filter(row -> path.equals(row.get("path")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry " + path + " in " + entries));
    }
}
