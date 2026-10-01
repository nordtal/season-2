package eu.nordtal.s2.steward.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The archive list is read while the thing it lists is being written, which is what these hold.
 */
class ArchiveRowTest {

    @TempDir
    private Path backups;

    @Test
    void theSizeAndTheSizeInWordsAreTheSameMomentNotTwo() throws IOException {
        final Path archive =
                Files.writeString(backups.resolve("mc-smp-data-20260913T041500Z.tar.zst"), "x".repeat(2048));

        final Map<String, Object> row = Archives.row(archive).orElseThrow();

        assertEquals(2048L, row.get("bytes"));
        assertEquals("2.0 KiB", row.get("human"));
        assertEquals(false, row.get("partial"));
    }

    @Test
    void aPartialIsListedAsOneBecauseADirectoryThatHidesThemIsLying() throws IOException {
        final Path running = Files.writeString(backups.resolve("mc-smp-data-20260913T041500Z.tar.zst.partial"), "half");

        assertEquals(true, Archives.row(running).orElseThrow().get("partial"));
    }

    @Test
    void anEntryThatVanishedLosesItsRowNotTheWholeListing() {
        // The rename that finishes a backup does exactly this to a path the directory stream already handed out.
        final Optional<Map<String, Object>> row = Archives.row(backups.resolve("gone.tar.zst"));

        assertTrue(row.isEmpty());
    }

    @Test
    void aDirectoryIsNotAnArchive() throws IOException {
        assertTrue(
                Archives.row(Files.createDirectory(backups.resolve("sources"))).isEmpty());
    }
}
