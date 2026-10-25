package eu.nordtal.season.bridgemod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeSettingsTest {

    @TempDir
    Path config;

    private Path write(final String text) throws IOException {
        return Files.writeString(config.resolve(BridgeSettings.FILE), text);
    }

    @Test
    void theFileGivesPortAndToken() throws IOException {
        assertEquals(
                new BridgeSettings(29415, "0123456789abcdef"),
                BridgeSettings.read(write("port=29415\ntoken=0123456789abcdef\n")));
    }

    @Test
    void aShortTokenOrAWrongPortIsRefused() throws IOException {
        final Path shortToken = write("port=29415\ntoken=abc\n");
        assertEquals(
                shortToken + " needs a token of at least 16 characters.",
                assertThrows(IllegalArgumentException.class, () -> BridgeSettings.read(shortToken))
                        .getMessage());
        final Path lowPort = write("port=80\ntoken=0123456789abcdef\n");
        assertEquals(
                lowPort + " needs a port from 1024 to 65535, not '80'.",
                assertThrows(IllegalArgumentException.class, () -> BridgeSettings.read(lowPort))
                        .getMessage());
    }

    @Test
    void theTokenIsCheckedAgainstTheFileAsItIsNow() throws IOException {
        final Path file = write("port=29415\ntoken=0123456789abcdef\n");
        assertTrue(BridgeSettings.accepts(file, "0123456789abcdef"));
        write("port=29415\ntoken=fedcba9876543210\n");
        assertFalse(BridgeSettings.accepts(file, "0123456789abcdef"));
        Files.delete(file);
        assertFalse(BridgeSettings.accepts(file, "fedcba9876543210"));
    }
}
