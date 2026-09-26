package eu.nordtal.s2.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * steward/76: the neighbour file a service writes beside its own config, naming the paths the
 * environment currently overrides - so that steward-worker can say a save through the interface
 * has no effect until the variable is removed, without depending on jcore's {@code ConfigHandle} or
 * reimplementing its {@code NORDTAL_<PREFIX>_<PATH>} naming rule a second time.
 */
class EnvOverrideFileTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("the neighbour file for access.yml is access.env-overrides.txt, beside it")
    void namesTheNeighbourFileTheSameWaySchemaWriterDoes() {
        final Path yml = directory.resolve("access.yml");

        assertEquals(directory.resolve("access.env-overrides.txt"), EnvOverrideFile.fileFor(yml));
    }

    @Test
    @DisplayName(".yaml is stripped the same way .yml is")
    void stripsDotYamlToo() {
        final Path yaml = directory.resolve("access.yaml");

        assertEquals(directory.resolve("access.env-overrides.txt"), EnvOverrideFile.fileFor(yaml));
    }

    @Test
    @DisplayName("a file with neither extension keeps its whole name")
    void keepsTheWholeNameWhenThereIsNoKnownExtension() {
        final Path properties = directory.resolve("voicechat-server.properties");

        assertEquals(
                directory.resolve("voicechat-server.properties.env-overrides.txt"),
                EnvOverrideFile.fileFor(properties));
    }

    @Test
    @DisplayName("reading before anything has written is absent, not an empty list")
    void readingBeforeAnyWriteIsAbsent() throws IOException {
        final Path yml = directory.resolve("access.yml");

        assertEquals(Optional.empty(), EnvOverrideFile.read(yml));
    }

    @Test
    @DisplayName("a round trip carries every path, in order")
    void writeThenReadCarriesEveryPathInOrder() throws IOException {
        final Path yml = directory.resolve("access.yml");
        final List<String> paths = List.of("guild-id", "roles.access", "languages");

        EnvOverrideFile.write(yml, paths);

        assertEquals(Optional.of(paths), EnvOverrideFile.read(yml));
    }

    @Test
    @DisplayName("writing an empty list still leaves a file behind - present-and-empty is its own fact")
    void writingNothingOverriddenStillLeavesAFile() throws IOException {
        final Path yml = directory.resolve("access.yml");

        EnvOverrideFile.write(yml, List.of());

        assertTrue(Files.isRegularFile(EnvOverrideFile.fileFor(yml)));
        assertEquals(Optional.of(List.of()), EnvOverrideFile.read(yml));
    }

    @Test
    @DisplayName("a second write replaces the first rather than appending to it")
    void aSecondWriteReplacesTheFirst() throws IOException {
        final Path yml = directory.resolve("access.yml");

        EnvOverrideFile.write(yml, List.of("guild-id", "languages"));
        EnvOverrideFile.write(yml, List.of("languages"));

        assertEquals(Optional.of(List.of("languages")), EnvOverrideFile.read(yml));
    }

    @Test
    @DisplayName("no .tmp file is left behind after a write")
    void leavesNoTemporaryFileBehind() throws IOException {
        final Path yml = directory.resolve("access.yml");

        EnvOverrideFile.write(yml, List.of("languages"));

        assertFalse(Files.exists(directory.resolve("access.env-overrides.txt.tmp")));
    }

    @Test
    @DisplayName("a blank line in a hand-edited neighbour file is skipped rather than read as a path")
    void blankLinesAreSkipped() throws IOException {
        final Path yml = directory.resolve("access.yml");
        Files.writeString(EnvOverrideFile.fileFor(yml), "guild-id\n\n  \nlanguages\n", StandardCharsets.UTF_8);

        assertEquals(Optional.of(List.of("guild-id", "languages")), EnvOverrideFile.read(yml));
    }
}
