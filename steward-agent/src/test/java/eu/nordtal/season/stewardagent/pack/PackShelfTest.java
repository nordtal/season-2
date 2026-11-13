package eu.nordtal.season.stewardagent.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The newest packs stay on the shelf, with the active one, and everything older goes. */
class PackShelfTest {

    @TempDir
    Path dir;

    private static String sha1(final int n) {
        return String.format("%040x", n);
    }

    /** Puts pack {@code n} on the shelf {@code n} minutes after a fixed time. */
    private void shelve(final int n) throws IOException {
        final Path file = Files.writeString(dir.resolve(sha1(n) + ".zip"), "pack " + n);
        Files.setLastModifiedTime(
                file, FileTime.from(Instant.parse("2020-01-01T12:00:00Z").plusSeconds(60L * n)));
    }

    private List<String> left() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void theNewestFiveStayAndTheOlderOnesGo() throws IOException {
        for (int n = 1; n <= 7; n++) {
            shelve(n);
        }

        final List<String> deleted = new PackShelf(dir, 5, Clock.systemUTC()).prune(sha1(7));

        assertEquals(
                List.of(sha1(1) + ".zip", sha1(2) + ".zip"),
                deleted.stream().sorted().toList());
        assertEquals(Stream.of(3, 4, 5, 6, 7).map(n -> sha1(n) + ".zip").toList(), left());
    }

    @Test
    void theActivePackStaysThoughItIsOld() throws IOException {
        for (int n = 1; n <= 7; n++) {
            shelve(n);
        }

        new PackShelf(dir, 5, Clock.systemUTC()).prune(sha1(1));

        assertEquals(Stream.of(1, 3, 4, 5, 6, 7).map(n -> sha1(n) + ".zip").toList(), left());
    }

    @Test
    void aFileThatIsNoPackIsLeftAlone() throws IOException {
        for (int n = 1; n <= 7; n++) {
            shelve(n);
        }
        Files.writeString(dir.resolve("README"), "not a pack");
        final Path old = Files.writeString(dir.resolve("build-123.zip.tmp"), "half");
        Files.setLastModifiedTime(old, FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));

        new PackShelf(dir, 5, Clock.systemUTC()).prune(sha1(7));

        assertTrue(Files.exists(dir.resolve("README")));
        assertTrue(Files.exists(old));
    }

    @Test
    void aPutPackIsReadableForCaddyAndCountsAsNewest() throws IOException {
        for (int n = 1; n <= 5; n++) {
            shelve(n);
        }
        final Path built = Files.createTempFile(dir, "build-", ".zip.tmp");
        Files.writeString(built, "pack 9");
        final PackShelf shelf = new PackShelf(dir, 5, Clock.systemUTC());

        shelf.put(built, sha1(9));
        shelf.prune(sha1(9));

        assertTrue(shelf.has(sha1(9)));
        assertFalse(Files.exists(built));
        assertFalse(shelf.has(sha1(1)));
        assertEquals(
                PosixFilePermissions.fromString("rw-r--r--"),
                Files.getPosixFilePermissions(dir.resolve(sha1(9) + ".zip")));
    }

    @Test
    void aPackBuiltAgainStaysAndBecomesNewest() throws IOException {
        for (int n = 1; n <= 6; n++) {
            shelve(n);
        }
        final Path built = Files.createTempFile(dir, "build-", ".zip.tmp");
        final PackShelf shelf = new PackShelf(dir, 5, Clock.systemUTC());

        shelf.put(built, sha1(1));
        shelf.prune(sha1(1));

        assertEquals(Stream.of(1, 3, 4, 5, 6).map(n -> sha1(n) + ".zip").toList(), left());
    }
}
