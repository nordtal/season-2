package eu.nordtal.s2.steward.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading {@code pack.yml} out of the proxy's volume without writing to it. */
class PackStateTest {

    @TempDir
    Path volume;

    @Test
    void aHashMadeOnlyOfDigitsIsTextNotANumber() throws IOException {
        // Guards against SnakeYAML reading forty zeroes as the long 0, matched against a hash never in the file.
        writePackYml("0000000000000000000000000000000000000000");

        assertEquals(
                "0000000000000000000000000000000000000000",
                PackState.read(volume).sha1());
    }

    @Test
    void theOrdinaryCaseUrlAndSha1ComeBackAsTheyAreWritten() throws IOException {
        writePackYml("6f1ed002ab5595859014ebf0951522d9d0f2ee34");

        final PackState state = PackState.read(volume);

        assertTrue(state.present());
        assertEquals("6f1ed002ab5595859014ebf0951522d9d0f2ee34", state.sha1());
        assertTrue(state.url().endsWith("nordtal-resource-pack-0.1.0.zip"), state.url());
    }

    @Test
    void noFileIsAbsentAndReadingItCreatesNothing() throws IOException {
        final PackState state = PackState.read(volume);

        assertFalse(state.present());
        assertNull(state.sha1());
        assertFalse(Files.exists(PackState.fileIn(volume)), "reading must never create the file");
    }

    @Test
    void anEmptyValueIsEmptyNotTheTextNull() throws IOException {
        final Path file = PackState.fileIn(volume);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "enabled: true\nurl:\nsha1:\n", StandardCharsets.UTF_8);

        final PackState state = PackState.read(volume);

        assertTrue(state.present());
        assertNull(state.url());
        assertNull(state.sha1());
    }

    private void writePackYml(final String sha1) throws IOException {
        final Path file = PackState.fileIn(volume);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                enabled: true
                url: https://github.com/nordtal/season-2/releases/download/v0.1.0/nordtal-resource-pack-0.1.0.zip
                sha1: %s
                """.formatted(sha1), StandardCharsets.UTF_8);
    }
}
