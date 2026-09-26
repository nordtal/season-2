package eu.nordtal.jcore.config.internal;

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
import org.jetbrains.annotations.NotNull;

/**
 * Writes a config file so that a crash, a kill or a full disk can never leave a half-written
 * file behind.
 * <p>
 * The old loader wrote straight into the destination. An interruption mid-write left a
 * truncated file, the next start failed to parse it, and the application carried on with silent
 * defaults. Here the content goes into a temporary file in the <i>same</i> directory (so the
 * move stays within one filesystem), is flushed to disk, and only then replaces the destination
 * with an atomic move. The destination is either the old content or the new content, never a
 * fragment.
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
    public static void write(final @NotNull Path file, final @NotNull String content) {
        final Path parent = parentOf(file);
        try {
            // The old code relied on `configFile.getParentFile()` being non-null, which it is not
            // for a bare relative name such as new File("config.yml").
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
                // Without the force() the bytes may still sit in the page cache when the move
                // completes, so a power loss right after would leave an empty file at the
                // destination - exactly the failure the atomic move is meant to prevent.
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
     * Copies {@code file} to {@code file + ".bak"} before it is modified, so an operator can
     * always get their previous content back. Does nothing if the file does not exist yet.
     *
     * @param file the file to back up
     * @return the backup path, or {@code null} if there was nothing to back up
     */
    public static Path backup(final @NotNull Path file) {
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
            // Some network filesystems refuse ATOMIC_MOVE. A plain replace is still far better
            // than writing into the destination directly, so fall back rather than fail.
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The parent of a bare relative path such as {@code config.yml} is {@code null}, which is
     * what made the old loader throw a NullPointerException. Resolve it to the working directory.
     */
    private static Path parentOf(final Path file) {
        final Path parent = file.toAbsolutePath().getParent();
        return parent == null ? file.toAbsolutePath().getRoot() : parent;
    }

    private static void deleteQuietly(final Path path) {
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
