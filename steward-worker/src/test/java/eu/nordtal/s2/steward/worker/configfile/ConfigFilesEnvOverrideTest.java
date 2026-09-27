package eu.nordtal.s2.steward.worker.configfile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.config.EnvOverrideFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ConfigEntry#environmentOverridden()} comes from the {@code <name>.env-overrides.txt} a service writes.
 *
 * It writes that file beside its own config file with {@link EnvOverrideFile#write} - never from re-deriving the
 * {@code NORDTAL_<PREFIX>_<PATH>} naming rule here, which this class exists to reject as a second opinion.
 *
 * Three states, not two, and this file exists to keep them apart: {@code null} (no service ever reported),
 * {@code true} (this exact path is overridden right now) and {@code false} (a service reported, and this path was
 * not among the overridden ones).
 */
class ConfigFilesEnvOverrideTest {

    @TempDir
    Path directory;

    @Test
    void absentWhenNoServiceEverWroteANeighbourFileNotTheSameAsNotOverridden() throws IOException {
        Files.writeString(directory.resolve("access.yml"), "languages: []\n");

        final ConfigDocument document = ConfigFiles.read(directory.resolve("access.yml"));

        assertNull(entry(document, "languages").environmentOverridden());
    }

    @Test
    void trueForExactlyThePathTheNeighbourFileNames() throws IOException {
        Files.writeString(directory.resolve("access.yml"), "guild-id: '1'\nlanguages:\n- tag: en\n");
        EnvOverrideFile.write(directory.resolve("access.yml"), List.of("languages"));

        final ConfigDocument document = ConfigFiles.read(directory.resolve("access.yml"));

        assertTrue(entry(document, "languages").environmentOverridden());
    }

    @Test
    void falseNotAbsentForAPathTheNeighbourFileDoesNotName() throws IOException {
        Files.writeString(directory.resolve("access.yml"), "guild-id: '1'\nlanguages:\n- tag: en\n");
        EnvOverrideFile.write(directory.resolve("access.yml"), List.of("languages"));

        final ConfigDocument document = ConfigFiles.read(directory.resolve("access.yml"));

        assertFalse(entry(document, "guild-id").environmentOverridden());
    }

    @Test
    void everyEntryIsFalseNotAbsentWhenTheServiceReportedNoOverridesAtAll() throws IOException {
        Files.writeString(directory.resolve("access.yml"), "guild-id: '1'\n");
        EnvOverrideFile.write(directory.resolve("access.yml"), List.of());

        final ConfigDocument document = ConfigFiles.read(directory.resolve("access.yml"));

        assertFalse(entry(document, "guild-id").environmentOverridden());
    }

    @Test
    void aDottedPathNestedUnderAHeadingIsMatchedByItsFullPathNotByItsLeafKey() throws IOException {
        Files.writeString(directory.resolve("service.yml"), "worker:\n  base-url: http://x\n");
        EnvOverrideFile.write(directory.resolve("service.yml"), List.of("worker.base-url"));

        final ConfigDocument document = ConfigFiles.read(directory.resolve("service.yml"));

        assertTrue(entry(document, "worker.base-url").environmentOverridden());
        // The heading itself is a different path and must not pick up the leaf's override.
        assertFalse(entry(document, "worker").environmentOverridden());
    }

    private static ConfigEntry entry(final ConfigDocument document, final String path) {
        return document.find(path)
                .orElseThrow(() -> new AssertionError("no entry " + path + " in "
                        + document.entries().stream().map(ConfigEntry::path).toList()));
    }
}
