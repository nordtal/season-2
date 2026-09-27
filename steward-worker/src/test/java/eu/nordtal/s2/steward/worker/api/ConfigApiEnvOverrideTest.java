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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code envOverridden} actually reaches the wire, and is absent rather than {@code false} when nobody checked. */
class ConfigApiEnvOverrideTest {

    @TempDir
    Path directory;

    @Test
    void theJsonTheBrowserIsSentCarriesEnvironmentoverriddenTrueForAnOverriddenPath() throws IOException {
        Files.writeString(directory.resolve("access.yml"), "languages:\n- tag: en\n");
        EnvOverrideFile.write(directory.resolve("access.yml"), List.of("languages"));

        assertEquals(
                true,
                entry("languages").get("environmentOverridden"),
                "api.ts declares ConfigEntry.environmentOverridden and configuration.tsx draws a"
                        + " warning badge from it - neither can do anything if the worker never"
                        + " sends it");
    }

    @Test
    void theJsonCarriesEnvironmentoverriddenFalseForAPathTheServiceReportedAsNotOverridden() throws IOException {
        Files.writeString(directory.resolve("access.yml"), "guild-id: '1'\nlanguages:\n- tag: en\n");
        EnvOverrideFile.write(directory.resolve("access.yml"), List.of("languages"));

        assertEquals(false, entry("guild-id").get("environmentOverridden"));
    }

    @Test
    void theJsonOmitsEnvironmentoverriddenEntirelyWhenNoServiceEverReported() throws IOException {
        Files.writeString(directory.resolve("access.yml"), "guild-id: '1'\n");
        // Deliberately no EnvOverrideFile.write call at all: the untouched case.

        assertFalse(
                entry("guild-id").containsKey("environmentOverridden"),
                "absent means \"this service never said\" - sending false here would be exactly the"
                        + " silent wrong answer this test guards against");
    }

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
