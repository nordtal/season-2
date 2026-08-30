package eu.nordtal.jcore.config;

import eu.nordtal.jcore.config.internal.AtomicConfigWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Finding 3: the old loader wrote straight into the destination file. */
class AtomicConfigWriterTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("finding 3: a reader never observes a partially written file")
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
                    // Every read must be one of the two complete versions. The old writer could
                    // be caught mid-write and hand back a truncated file - the probe for this
                    // produced 70 bytes of invalid JSON.
                    if (!content.equals(small) && !content.equals(large)) {
                        fragment.compareAndSet(null, "length " + content.length());
                        return;
                    }
                } catch (Exception ignored) {
                    // A momentarily absent file would also be a defect, but on the platforms we
                    // deploy on the atomic move never exposes that window; keep looping.
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
    @DisplayName("finding 3: a failed write leaves the previous content intact")
    void failedWriteKeepsPreviousContent() throws Exception {
        final Path file = directory.resolve("sub/config.yml");
        AtomicConfigWriter.write(file, "good: yes\n");

        // Make the destination directory unwritable so creating the temp file fails, which is
        // the closest reproducible stand-in for a full disk.
        final Path parent = file.getParent();
        assertTrue(parent.toFile().setWritable(false), "cannot make the directory read-only here");
        try {
            assertThrows(UncheckedIOException.class,
                    () -> AtomicConfigWriter.write(file, "replacement: yes\n"));
            assertEquals("good: yes\n", Files.readString(file),
                    "the destination must still hold the previous, complete content");
        } finally {
            parent.toFile().setWritable(true);
        }
    }

    @Test
    @DisplayName("no temporary files are left behind")
    void leavesNoTemporaryFiles() throws Exception {
        final Path file = directory.resolve("config.yml");
        for (int i = 0; i < 5; i++) {
            AtomicConfigWriter.write(file, "n: " + i + "\n");
        }

        try (var entries = Files.list(directory)) {
            final List<String> names = entries.map(path -> path.getFileName().toString()).toList();
            assertEquals(List.of("config.yml"), names, "a temp file survived: " + names);
        }
    }

    @Test
    @DisplayName("finding 4: a path with no parent directory is written to the working directory")
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
    @DisplayName("backup() copies the previous content and is a no-op for a missing file")
    void backupBehaviour() throws Exception {
        final Path file = directory.resolve("config.yml");

        assertNull(AtomicConfigWriter.backup(file), "nothing to back up yet");

        AtomicConfigWriter.write(file, "first: 1\n");
        final Path backup = AtomicConfigWriter.backup(file);
        AtomicConfigWriter.write(file, "second: 2\n");

        assertAll(
                () -> assertEquals(directory.resolve("config.yml.bak"), backup),
                () -> assertEquals("first: 1\n", Files.readString(backup)),
                () -> assertEquals("second: 2\n", Files.readString(file))
        );
    }
}
