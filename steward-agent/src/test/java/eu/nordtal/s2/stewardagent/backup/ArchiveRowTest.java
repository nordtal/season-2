package eu.nordtal.s2.stewardagent.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.internalapi.agent.AgentWire;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

        final AgentWire.Archive row = ArchiveFiles.row(archive).orElseThrow();

        assertEquals(2048L, row.bytes());
        assertEquals("2.0 KiB", row.human());
        assertEquals(false, row.partial());
    }

    @Test
    void aFinishedArchiveSaysWhatARestoreOfItReplacesAndAPartialSaysNothing() throws IOException {
        assertEquals(
                "nordtal-s2_mc-smp",
                ArchiveFiles.row(Files.writeString(backups.resolve("nordtal-s2_mc-smp-20260913T041500Z.tar.zst"), "x"))
                        .orElseThrow()
                        .restoresInto());
        assertEquals(
                "nordtal",
                ArchiveFiles.row(Files.writeString(backups.resolve("nordtal-20260913T041500Z.dump"), "x"))
                        .orElseThrow()
                        .restoresInto());
        assertEquals(
                null,
                ArchiveFiles.row(Files.writeString(backups.resolve("nordtal-20260913T041500Z.dump.partial"), "x"))
                        .orElseThrow()
                        .restoresInto());
    }

    @Test
    void aPartialIsListedAsOneBecauseADirectoryThatHidesThemIsLying() throws IOException {
        final Path running = Files.writeString(backups.resolve("mc-smp-data-20260913T041500Z.tar.zst.partial"), "half");

        assertTrue(ArchiveFiles.row(running).orElseThrow().partial());
    }

    @Test
    void anEntryThatVanishedLosesItsRowNotTheWholeListing() {
        // The rename that finishes a backup does exactly this to a path the directory stream already handed out.
        final Optional<AgentWire.Archive> row = ArchiveFiles.row(backups.resolve("gone.tar.zst"));

        assertTrue(row.isEmpty());
    }

    @Test
    void aDirectoryIsNotAnArchive() throws IOException {
        assertTrue(ArchiveFiles.row(Files.createDirectory(backups.resolve("sources")))
                .isEmpty());
    }
}
