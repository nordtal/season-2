package eu.nordtal.s2.updater.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading {@code pack.yml} out of the proxy's volume without writing to it. */
class PackStateTest {

    @TempDir
    Path volume;

    @Test
    @DisplayName("a hash made only of digits is text, not a number")
    void doesNotCoerceANumericLookingHash() throws IOException {
        // The bug this test exists for, found 2026-09-01: SnakeYAML's implicit resolvers turned
        // forty zeroes into the long 0, so the comparison ran against a hash that was never in the
        // file - and it reported the pack as changed from "0" to itself.
        writePackYml("0000000000000000000000000000000000000000");

        assertEquals("0000000000000000000000000000000000000000", PackState.read(volume).sha1());
    }

    @Test
    @DisplayName("the ordinary case: url and sha1 come back as they are written")
    void readsBothValues() throws IOException {
        writePackYml("6f1ed002ab5595859014ebf0951522d9d0f2ee34");

        final PackState state = PackState.read(volume);

        assertTrue(state.present());
        assertEquals("6f1ed002ab5595859014ebf0951522d9d0f2ee34", state.sha1());
        assertTrue(state.url().endsWith("nordtal-resource-pack-0.1.0.zip"), state.url());
    }

    @Test
    @DisplayName("no file is absent, and reading it creates nothing")
    void absentWithoutCreatingAnything() throws IOException {
        final PackState state = PackState.read(volume);

        assertFalse(state.present());
        assertNull(state.sha1());
        assertFalse(Files.exists(PackState.fileIn(volume)), "reading must never create the file");
    }

    @Test
    @DisplayName("an empty value is empty, not the text null")
    void emptyValuesStayEmpty() throws IOException {
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
