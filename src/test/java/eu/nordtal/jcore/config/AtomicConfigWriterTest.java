package eu.nordtal.jcore.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Finding 3: the old loader wrote straight into the destination file. */
class AtomicConfigWriterTest {

    @TempDir
    Path directory;

    @Test
    void readerNeverSeesAFragment() throws Exception {
        final Path file = directory.resolve("config.yml");
        final String small = "value: " + "a".repeat(2_000) + "\n";
        final String large = "value: " + "b".repeat(2_000_000) + "\n";
        AtomicConfigWriter.write(file, small);

        final AtomicReference<String> fragment = new AtomicReference<>();
        final AtomicBoolean stop = new AtomicBoolean();
        final CountDownLatch reading = new CountDownLatch(1);

        final Thread reader = new Thread(() -> {
            reading.countDown();
            while (!stop.get()) {
                try {
                    final String content = Files.readString(file);
                    // Every read must be one of the two complete versions, never a fragment mid-write.
                    if (!content.equals(small) && !content.equals(large)) {
                        fragment.compareAndSet(null, "length " + content.length());
                        return;
                    }
                } catch (Exception ignored) {
                    // A momentarily absent file is not a defect here; the atomic move never exposes that window.
                }
            }
        });
        reader.start();
        reading.await(5, TimeUnit.SECONDS);

        for (int i = 0; i < 40; i++) {
            AtomicConfigWriter.write(file, i % 2 == 0 ? large : small);
        }
        stop.set(true);
        reader.join(TimeUnit.SECONDS.toMillis(10));

        assertNull(fragment.get(), "a reader saw a partially written file: " + fragment.get());
    }

    @Test
    void failedWriteKeepsPreviousContent() throws Exception {
        // A size-limited in-memory filesystem simulates a full disk; permission bits don't fail for uid 0.
        try (FileSystem full = Jimfs.newFileSystem(
                Configuration.unix().toBuilder().setMaxSize(64 * 1024).build())) {
            final Path file = full.getPath("/config/sub/config.yml");
            AtomicConfigWriter.write(file, "good: yes\n");

            final String tooBig = "replacement: " + "x".repeat(1_000_000) + "\n";
            assertThrows(UncheckedIOException.class, () -> AtomicConfigWriter.write(file, tooBig));
            assertEquals(
                    "good: yes\n",
                    Files.readString(file),
                    "the destination must still hold the previous, complete content");
        }
    }

    /** Nothing is left lying around either - the temp file that did not fit has to go. */
    @Test
    void failedWriteLeavesNoTemporaryFile() throws Exception {
        try (FileSystem full = Jimfs.newFileSystem(
                Configuration.unix().toBuilder().setMaxSize(64 * 1024).build())) {
            final Path file = full.getPath("/config/config.yml");
            AtomicConfigWriter.write(file, "good: yes\n");

            assertThrows(UncheckedIOException.class, () -> AtomicConfigWriter.write(file, "x".repeat(1_000_000)));

            try (var entries = Files.list(file.getParent())) {
                final List<String> names =
                        entries.map(path -> path.getFileName().toString()).toList();
                assertEquals(List.of("config.yml"), names, "a temp file survived: " + names);
            }
        }
    }

    @Test
    void leavesNoTemporaryFiles() throws Exception {
        final Path file = directory.resolve("config.yml");
        for (int i = 0; i < 5; i++) {
            AtomicConfigWriter.write(file, "n: " + i + "\n");
        }

        try (var entries = Files.list(directory)) {
            final List<String> names =
                    entries.map(path -> path.getFileName().toString()).toList();
            assertEquals(List.of("config.yml"), names, "a temp file survived: " + names);
        }
    }

    @Test
    void bareRelativePathHasNoParent() throws Exception {
        final Path bare = Path.of("jcore-atomic-test-" + System.nanoTime() + ".yml");
        try {
            AtomicConfigWriter.write(bare, "ok: true\n");
            assertEquals("ok: true\n", Files.readString(bare));
        } finally {
            Files.deleteIfExists(bare);
        }
    }

    @Test
    void backupBehaviour() throws Exception {
        final Path file = directory.resolve("config.yml");

        assertNull(AtomicConfigWriter.backup(file), "nothing to back up yet");

        AtomicConfigWriter.write(file, "first: 1\n");
        final Path backup = AtomicConfigWriter.backup(file);
        AtomicConfigWriter.write(file, "second: 2\n");

        assertAll(
                () -> assertEquals(directory.resolve("config.yml.bak"), backup),
                () -> assertEquals("first: 1\n", Files.readString(backup)),
                () -> assertEquals("second: 2\n", Files.readString(file)));
    }
}
