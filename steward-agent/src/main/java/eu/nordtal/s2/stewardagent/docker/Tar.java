package eu.nordtal.s2.stewardagent.docker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Reads the first entry of a ustar stream, which is all the daemon's archive route sends for one file. */
final class Tar {

    private static final int BLOCK = 512;
    private static final int SIZE_OFFSET = 124;
    private static final int SIZE_LENGTH = 12;
    private static final int TYPE_OFFSET = 156;

    private Tar() {}

    /**
     * Writes the first entry of {@code tar} to {@code target}, through a temporary file beside it.
     *
     * @throws IOException if the header is cut short, the entry is not a regular file or the body ends early
     */
    static void firstFile(final InputStream tar, final Path target) throws IOException {
        final byte[] header = tar.readNBytes(BLOCK);
        if (header.length < BLOCK) {
            throw new IOException("the archive ended inside its first header");
        }
        final byte type = header[TYPE_OFFSET];
        if (type != '0' && type != 0) {
            throw new IOException("the archive's first entry is not a regular file (type " + (char) type + ")");
        }
        final long size = Long.parseLong(
                new String(header, SIZE_OFFSET, SIZE_LENGTH, StandardCharsets.US_ASCII)
                        .replace("\0", "")
                        .trim(),
                8);
        final Path partial = target.resolveSibling(target.getFileName() + ".part");
        try {
            copy(tar, partial, size);
        } catch (final IOException failed) {
            Files.deleteIfExists(partial);
            throw failed;
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void copy(final InputStream tar, final Path partial, final long size) throws IOException {
        try (OutputStream out = Files.newOutputStream(partial)) {
            final byte[] buffer = new byte[64 * 1024];
            long left = size;
            while (left > 0) {
                final int read = tar.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (read < 0) {
                    throw new IOException("the archive ended " + left + " bytes before its entry did");
                }
                out.write(buffer, 0, read);
                left -= read;
            }
        }
    }
}
