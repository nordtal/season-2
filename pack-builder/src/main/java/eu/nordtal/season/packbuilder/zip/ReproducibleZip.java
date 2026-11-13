package eu.nordtal.season.packbuilder.zip;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A zip whose bytes depend on its files alone: files only, sorted by path, deflated, all at one fixed time.
 * The time is written as local time without a time zone, so the zip is the same wherever it is built.
 */
public final class ReproducibleZip {

    /** The time every entry carries: the first of February 1980, the earliest a zip writes everywhere. */
    private static final LocalDateTime TIME = LocalDateTime.of(1980, 2, 1, 0, 0);

    private ReproducibleZip() {}

    /** The zip of {@code files}, each path a file's path in the zip. */
    public static byte[] write(final Map<String, byte[]> files) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.setMethod(ZipOutputStream.DEFLATED);
            for (final Map.Entry<String, byte[]> file : new TreeMap<>(files).entrySet()) {
                final ZipEntry entry = new ZipEntry(file.getKey());
                entry.setTimeLocal(TIME);
                zip.putNextEntry(entry);
                zip.write(file.getValue());
                zip.closeEntry();
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** The SHA-1 of {@code bytes} in lower-case hex. */
    public static String sha1(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("the JDK has no SHA-1", e);
        }
    }
}
