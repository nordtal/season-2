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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests the file a service writes beside its config, naming the paths the environment overrides. */
class EnvOverrideFileTest {

    @TempDir
    Path directory;

    @Test
    void theNeighbourFileForAccessYmlIsAccessEnvOverridesTxtBesideIt() {
        final Path yml = directory.resolve("access.yml");

        assertEquals(directory.resolve("access.env-overrides.txt"), EnvOverrideFile.fileFor(yml));
    }

    @Test
    void yamlIsStrippedTheSameWayYmlIs() {
        final Path yaml = directory.resolve("access.yaml");

        assertEquals(directory.resolve("access.env-overrides.txt"), EnvOverrideFile.fileFor(yaml));
    }

    @Test
    void aFileWithNeitherExtensionKeepsItsWholeName() {
        final Path properties = directory.resolve("voicechat-server.properties");

        assertEquals(
                directory.resolve("voicechat-server.properties.env-overrides.txt"),
                EnvOverrideFile.fileFor(properties));
    }

    @Test
    void readingBeforeAnythingHasWrittenIsAbsentNotAnEmptyList() throws IOException {
        final Path yml = directory.resolve("access.yml");

        assertEquals(Optional.empty(), EnvOverrideFile.read(yml));
    }

    @Test
    void aRoundTripCarriesEveryPathInOrder() throws IOException {
        final Path yml = directory.resolve("access.yml");
        final List<String> paths = List.of("guild-id", "roles.access", "languages");

        EnvOverrideFile.write(yml, paths);

        assertEquals(Optional.of(paths), EnvOverrideFile.read(yml));
    }

    @Test
    void writingAnEmptyListStillLeavesAFileBehindPresentAndEmptyIsItsOwnFact() throws IOException {
        final Path yml = directory.resolve("access.yml");

        EnvOverrideFile.write(yml, List.of());

        assertTrue(Files.isRegularFile(EnvOverrideFile.fileFor(yml)));
        assertEquals(Optional.of(List.of()), EnvOverrideFile.read(yml));
    }

    @Test
    void aSecondWriteReplacesTheFirstRatherThanAppendingToIt() throws IOException {
        final Path yml = directory.resolve("access.yml");

        EnvOverrideFile.write(yml, List.of("guild-id", "languages"));
        EnvOverrideFile.write(yml, List.of("languages"));

        assertEquals(Optional.of(List.of("languages")), EnvOverrideFile.read(yml));
    }

    @Test
    void noTmpFileIsLeftBehindAfterAWrite() throws IOException {
        final Path yml = directory.resolve("access.yml");

        EnvOverrideFile.write(yml, List.of("languages"));

        assertFalse(Files.exists(directory.resolve("access.env-overrides.txt.tmp")));
    }

    @Test
    void aBlankLineInAHandEditedNeighbourFileIsSkippedRatherThanReadAsAPath() throws IOException {
        final Path yml = directory.resolve("access.yml");
        Files.writeString(EnvOverrideFile.fileFor(yml), "guild-id\n\n  \nlanguages\n", StandardCharsets.UTF_8);

        assertEquals(Optional.of(List.of("guild-id", "languages")), EnvOverrideFile.read(yml));
    }
}
