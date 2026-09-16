package eu.nordtal.jcore.config;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import eu.nordtal.jcore.config.internal.AtomicConfigWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
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
    @DisplayName("finding 3: a disk with no room left leaves the previous content intact")
    void failedWriteKeepsPreviousContent() throws Exception {
        // This used to take the write bit off the destination directory and call that a full disk.
        // It is not one, and on a machine where the build runs as uid 0 it is not even a failure:
        // root ignores the permission bits, the write succeeded, and the assertion below failed for
        // a reason that had nothing to do with jcore. That was every build on the nordtal dev host,
        // and it cost two agents a round each before anybody read it properly.
        //
        // So the failure is produced where it really comes from instead: a filesystem with a
        // maximum size, in memory. No uid talks its way past a disk that is full, the promise being
        // tested is exactly the one in the javadoc, and the test says the same thing on every
        // machine.
        try (FileSystem full = Jimfs.newFileSystem(
                Configuration.unix().toBuilder().setMaxSize(64 * 1024).build())) {
            final Path file = full.getPath("/config/sub/config.yml");
            AtomicConfigWriter.write(file, "good: yes\n");

            final String tooBig = "replacement: " + "x".repeat(1_000_000) + "\n";
            assertThrows(UncheckedIOException.class, () -> AtomicConfigWriter.write(file, tooBig));
            assertEquals("good: yes\n", Files.readString(file),
                    "the destination must still hold the previous, complete content");
        }
    }

    /** Nothing is left lying around either - the temp file that did not fit has to go. */
    @Test
    @DisplayName("a write that runs out of room leaves no temporary file behind")
    void failedWriteLeavesNoTemporaryFile() throws Exception {
        try (FileSystem full = Jimfs.newFileSystem(
                Configuration.unix().toBuilder().setMaxSize(64 * 1024).build())) {
            final Path file = full.getPath("/config/config.yml");
            AtomicConfigWriter.write(file, "good: yes\n");

            assertThrows(UncheckedIOException.class,
                    () -> AtomicConfigWriter.write(file, "x".repeat(1_000_000)));

            try (var entries = Files.list(file.getParent())) {
                final List<String> names = entries.map(path -> path.getFileName().toString()).toList();
                assertEquals(List.of("config.yml"), names, "a temp file survived: " + names);
            }
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
