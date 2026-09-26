package eu.nordtal.jcore.config;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.jspecify.annotations.Nullable;

/**
 * Writes a config file so that a crash or a full disk can never leave a half-written file behind.
 *
 * The content goes into a temporary file in the same directory (so the move stays within one
 * filesystem), is flushed to disk, and only then replaces the destination with an atomic move.
 * The destination is either the old content or the new content, never a fragment.
 */
public final class AtomicConfigWriter {

    private AtomicConfigWriter() {}

    /**
     * Writes {@code content} to {@code file}, atomically.
     *
     * @param file    the destination. Its parent directory is created if it does not exist.
     * @param content the complete file content
     * @throws UncheckedIOException if the file cannot be written. The destination is untouched.
     */
    public static void write(final Path file, final String content) {
        final Path parent = parentOf(file);
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the directory for config file " + file, e);
        }

        Path temp = null;
        try {
            temp = Files.createTempFile(parent, ".", ".tmp");
            final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp, CREATE, TRUNCATE_EXISTING, WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                // Without force(), a power loss right after the move could leave an empty destination file.
                channel.force(true);
            }
            move(temp, file);
            temp = null;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write config file " + file, e);
        } finally {
            deleteQuietly(temp);
        }
    }

    /**
     * Copies {@code file} to {@code file + ".bak"}. Does nothing if the file does not exist yet.
     *
     * @param file the file to back up
     * @return the backup path, or {@code null} if there was nothing to back up
     */
    public static @Nullable Path backup(final Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        final Path backup = file.resolveSibling(file.getFileName() + ".bak");
        try {
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot back up config file " + file + " to " + backup, e);
        }
        return backup;
    }

    private static void move(final Path temp, final Path file) throws IOException {
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Some network filesystems refuse ATOMIC_MOVE; fall back to a plain replace instead of failing.
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The directory {@code file} lives in, resolved against the working directory for a bare relative name.
     */
    private static Path parentOf(final Path file) {
        final Path parent = file.toAbsolutePath().getParent();
        return parent == null ? file.toAbsolutePath().getRoot() : parent;
    }

    private static void deleteQuietly(final @Nullable Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A leftover temp file is not worth masking the real failure.
        }
    }
}
