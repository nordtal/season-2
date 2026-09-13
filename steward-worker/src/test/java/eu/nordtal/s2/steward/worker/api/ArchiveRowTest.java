package eu.nordtal.s2.steward.worker.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The archive list is read while the thing it lists is being written, which is what these hold.
 */
class ArchiveRowTest {

    @TempDir
    private Path backups;

    @Test
    @DisplayName("the size and the size in words are the same moment, not two")
    void oneStatAnswersBoth() throws IOException {
        final Path archive = Files.writeString(backups.resolve("mc-smp-data-20260913T041500Z.tar.zst"),
                "x".repeat(2048));

        final Map<String, Object> row = WorkerApi.archiveRow(archive).orElseThrow();

        assertEquals(2048L, row.get("bytes"));
        assertEquals("2.0 KiB", row.get("human"));
        assertEquals(false, row.get("partial"));
    }

    @Test
    @DisplayName("a .partial is listed as one, because a directory that hides them is lying")
    void aPartialSaysSo() throws IOException {
        final Path running = Files.writeString(
                backups.resolve("mc-smp-data-20260913T041500Z.tar.zst.partial"), "half");

        assertEquals(true, WorkerApi.archiveRow(running).orElseThrow().get("partial"));
    }

    @Test
    @DisplayName("an entry that vanished loses its row, not the whole listing")
    void aVanishedEntryIsNotAnError() {
        // The rename that finishes a backup does exactly this to a path the directory stream has
        // already handed out. Thrown, it emptied the backup page - the screen an admin reads as
        // "the backups are gone".
        final Optional<Map<String, Object>> row = WorkerApi.archiveRow(backups.resolve("gone.tar.zst"));

        assertTrue(row.isEmpty());
    }

    @Test
    void aDirectoryIsNotAnArchive() throws IOException {
        assertTrue(WorkerApi.archiveRow(Files.createDirectory(backups.resolve("sources"))).isEmpty());
    }
}
