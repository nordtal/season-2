package eu.nordtal.s2.steward.ui.configfile;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding the config files under the mount.
 *
 * <p>The layout is one directory per compose service, and inside it whatever that service's volume
 * happens to contain - a single {@code steward.yml} for the worker, a whole
 * {@code plugins/<name>/config.yml} tree for a Minecraft server.</p>
 */
class ConfigFilesDiscoverTest {

    @TempDir
    Path root;

    @Test
    void everyYmlIsFoundAndSortedByServiceThenName() throws IOException {
        write("steward-worker/steward.yml");
        write("steward-worker/database.yml");
        write("smp/nordtal-smp/config.yml");
        write("smp/display-tags/config.yml");
        write("discord-bot/config.yml");

        final List<ConfigLocation> found = ConfigFiles.discover(root);

        assertEquals(List.of(
                "discord-bot/config.yml",
                "smp/display-tags/config.yml",
                "smp/nordtal-smp/config.yml",
                "steward-worker/database.yml",
                "steward-worker/steward.yml"),
                found.stream().map(l -> l.service() + "/" + l.name()).toList());
    }

    @Test
    void theServiceIsTheDirectoryAndTheNameIsEverythingUnderIt() throws IOException {
        write("smp/nordtal-smp/config.yml");

        final ConfigLocation location = ConfigFiles.discover(root).getFirst();

        assertEquals("smp", location.service());
        assertEquals("nordtal-smp/config.yml", location.name());
        assertEquals(root.resolve("smp/nordtal-smp/config.yml"), location.file());
        assertTrue(location.writable());
    }

    @Test
    void anythingThatIsNotAYmlIsIgnored() throws IOException {
        write("smp/config.yml");
        write("smp/server.properties");
        write("smp/config.yaml");
        write("smp/ops.json");

        assertEquals(List.of("config.yml"), ConfigFiles.discover(root).stream()
                .map(ConfigLocation::name).toList());
    }

    @Test
    void aFileLyingDirectlyInTheRootIsListedWithNoService() throws IOException {
        write("loose.yml");

        final ConfigLocation location = ConfigFiles.discover(root).getFirst();

        assertEquals("", location.service());
        assertEquals("loose.yml", location.name());
    }

    @Test
    void aRootThatIsNotThereIsAnEmptyListAndNotAFailure() {
        assertEquals(List.of(), ConfigFiles.discover(root.resolve("never-mounted")));
    }

    @Test
    void anEmptyRootIsAnEmptyList() {
        assertEquals(List.of(), ConfigFiles.discover(root));
    }

    @Test
    void aFileInAReadOnlyDirectoryIsNotWritable() throws IOException {
        final Path service = Files.createDirectories(root.resolve("readonly"));
        final Path file = Files.writeString(service.resolve("config.yml"), "port: 8080\n");
        Files.setPosixFilePermissions(service, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            // root ignores the permission bits, and this project's containers run as root. Then
            // there is nothing to assert here and saying so is better than a green test that
            // checked nothing.
            Assumptions.assumeFalse(Files.isWritable(service),
                    "running as a user that can write a read-only directory");

            assertFalse(ConfigFiles.discover(root).getFirst().writable(),
                    "the write is a rename into the directory, so the directory has to be writable");
        } finally {
            Files.setPosixFilePermissions(service, PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.deleteIfExists(file);
        }
    }

    private void write(final String relative) throws IOException {
        final Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "port: 8080\n");
    }
}
