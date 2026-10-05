package eu.nordtal.season.stewardagent.docker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The one tar entry the daemon's archive route sends for a file, as a jar copied out of an image arrives. */
class TarTest {

    @TempDir
    Path directory;

    private static byte[] entry(final byte type, final byte[] body, final int bodyBytesSent) {
        final byte[] header = new byte[512];
        final byte[] name = "app.jar".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, header, 0, name.length);
        final byte[] size = String.format("%011o\0", body.length).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(size, 0, header, 124, size.length);
        header[156] = type;
        final byte[] tar = new byte[512 + bodyBytesSent];
        System.arraycopy(header, 0, tar, 0, 512);
        System.arraycopy(body, 0, tar, 512, bodyBytesSent);
        return tar;
    }

    @Test
    void writesTheFirstEntrysBytesExactly() throws IOException {
        final byte[] body = new byte[70_000];
        Arrays.fill(body, (byte) 7);
        final Path target = directory.resolve("copy.jar");

        Tar.firstFile(new ByteArrayInputStream(entry((byte) '0', body, body.length)), target);

        assertArrayEquals(body, Files.readAllBytes(target));
    }

    @Test
    void aStreamThatEndsEarlyLeavesNoFileBehind() {
        final byte[] body = new byte[2_000];
        final Path target = directory.resolve("copy.jar");

        assertThrows(
                IOException.class,
                () -> Tar.firstFile(new ByteArrayInputStream(entry((byte) '0', body, 1_000)), target));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(directory.resolve("copy.jar.part")));
    }

    @Test
    void aDirectoryIsRefused() {
        assertThrows(
                IOException.class,
                () -> Tar.firstFile(
                        new ByteArrayInputStream(entry((byte) '5', new byte[0], 0)), directory.resolve("copy.jar")));
    }
}
