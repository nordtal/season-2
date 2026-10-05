package eu.nordtal.season.stewardagent.backup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A restore empties a server's data directory around the plugins folder another bind mount sits on. */
class EmptyingAVolumeTest {

    @TempDir
    Path volume;

    @Test
    void anEmptyDirectoryTheKernelRefusesAsBusyIsKeptAndEverythingElseGoes() throws IOException {
        final Path plugins = Files.createDirectories(volume.resolve("plugins"));
        Files.writeString(volume.resolve("server.properties"), "level-name=nordtal\n");
        Files.writeString(volume.resolve("spigot.yml"), "settings: {}\n");
        Files.createDirectories(volume.resolve("nordtal/region"));
        Files.writeString(volume.resolve("nordtal/level.dat"), "level");

        TarSnapshots.empty(volume, path -> {
            if (path.equals(plugins)) {
                throw new FileSystemException(path.toString(), null, "Device or resource busy");
            }
            Files.delete(path);
        });

        assertTrue(Files.isDirectory(plugins));
        try (Stream<Path> left = Files.list(volume)) {
            assertTrue(left.allMatch(plugins::equals), "only the busy mount point stays");
        }
    }

    @Test
    void aBusyDirectoryThatStillHoldsSomethingIsAFailure() throws IOException {
        final Path plugins = Files.createDirectories(volume.resolve("plugins"));
        Files.writeString(plugins.resolve("kept.jar"), "jar");

        assertThrows(
                FileSystemException.class,
                () -> TarSnapshots.empty(volume, path -> {
                    if (path.equals(plugins) || path.startsWith(plugins)) {
                        throw new FileSystemException(path.toString(), null, "Device or resource busy");
                    }
                    Files.delete(path);
                }));
    }

    @Test
    void anyOtherRefusalIsAFailure() throws IOException {
        Files.writeString(volume.resolve("server.properties"), "level-name=nordtal\n");

        assertThrows(
                FileSystemException.class,
                () -> TarSnapshots.empty(volume, path -> {
                    throw new FileSystemException(path.toString(), null, "Permission denied");
                }));
        assertFalse(Files.notExists(volume.resolve("server.properties")));
    }
}
