package eu.nordtal.season.underground.anvil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntPredicate;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.jspecify.annotations.Nullable;

/** Reads and writes one Anvil region file as a map from the chunk's index, x + z * 32, to its NBT. */
public final class Region {

    /** The number of chunks along one side of a region. */
    public static final int SIDE = 32;

    private static final int SECTOR = 4096;
    private static final int EXTERNAL = 0x80;
    private static final int MAX_SECTORS = 255;

    private Region() {}

    /** Returns the path of the region file with these region coordinates in a directory. */
    public static Path file(final Path directory, final int regionX, final int regionZ) {
        return directory.resolve("r." + regionX + "." + regionZ + ".mca");
    }

    /** Reads every chunk of a region file, or an empty map when the file is missing. */
    public static Map<Integer, Map<String, Object>> read(final Path file) throws IOException {
        return read(file, index -> true);
    }

    /** Reads the chunks of a region file whose index passes the filter. */
    public static Map<Integer, Map<String, Object>> read(final Path file, final IntPredicate wanted)
            throws IOException {
        final Map<Integer, Map<String, Object>> chunks = new TreeMap<>();
        if (!Files.exists(file)) {
            return chunks;
        }
        final byte[] data = Files.readAllBytes(file);
        if (data.length < 2 * SECTOR) {
            return chunks;
        }
        for (int index = 0; index < SIDE * SIDE; index++) {
            final int offset = int24(data, index * 4) * SECTOR;
            if (offset == 0 || offset + 5 > data.length || !wanted.test(index)) {
                continue;
            }
            final int length = int32(data, offset);
            final int compression = data[offset + 4] & 0xFF;
            final InputStream stored = (compression & EXTERNAL) == 0
                    ? new ByteArrayInputStream(data, offset + 5, length - 1)
                    : new ByteArrayInputStream(Files.readAllBytes(external(file, index)));
            chunks.put(index, Nbt.read(inflate(stored, compression & ~EXTERNAL)));
        }
        return chunks;
    }

    /**
     * Writes the chunks as a new region file in place of the old one, and an external file for each oversized chunk.
     * An external file of an earlier chunk that now fits stays behind, and nothing reads it.
     * The table of times stays empty: the game reads none of it, and the same input gives the same file.
     */
    public static void write(final Path file, final Map<Integer, Map<String, Object>> chunks) throws IOException {
        final ByteArrayOutputStream body = new ByteArrayOutputStream(1 << 24);
        final byte[] header = new byte[2 * SECTOR];
        int sector = 2;
        for (final Map.Entry<Integer, Map<String, Object>> chunk : new TreeMap<>(chunks).entrySet()) {
            final int index = chunk.getKey();
            byte[] stored = deflate(Nbt.write(chunk.getValue()));
            int compression = 2;
            if ((stored.length + 5 + SECTOR - 1) / SECTOR > MAX_SECTORS) {
                Files.write(external(file, index), stored);
                stored = new byte[0];
                compression |= EXTERNAL;
            }
            final int length = stored.length + 1;
            final int sectors = (length + 4 + SECTOR - 1) / SECTOR;
            putInt24(header, index * 4, sector);
            header[index * 4 + 3] = (byte) sectors;
            final byte[] lead = new byte[5];
            putInt32(lead, 0, length);
            lead[4] = (byte) compression;
            body.write(lead);
            body.write(stored);
            body.write(new byte[sectors * SECTOR - length - 4]);
            sector += sectors;
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(header);
            body.writeTo(out);
        }
    }

    /** Returns the region coordinates in a file name such as r.3.-2.mca, or null for another name. */
    public static int @Nullable [] coordinates(final Path file) {
        final String[] parts = file.getFileName().toString().split("\\.", -1);
        if (parts.length != 4 || !parts[0].equals("r") || !parts[3].equals("mca")) {
            return null;
        }
        return new int[] {Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
    }

    private static Path external(final Path file, final int index) throws IOException {
        final int[] region = coordinates(file);
        if (region == null) {
            throw new IOException(file + " is not named like a region file.");
        }
        final int chunkX = region[0] * SIDE + index % SIDE;
        final int chunkZ = region[1] * SIDE + index / SIDE;
        return file.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc");
    }

    private static byte[] inflate(final InputStream stored, final int compression) throws IOException {
        final InputStream in = switch (compression) {
            case 1 -> new GZIPInputStream(stored);
            case 2 -> new InflaterInputStream(stored);
            case 3 -> stored;
            default -> throw new IOException("Unknown chunk compression " + compression + ".");
        };
        try (in) {
            return in.readAllBytes();
        }
    }

    private static byte[] deflate(final byte[] raw) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 15);
        final Deflater deflater = new Deflater(4);
        try (DeflaterOutputStream out = new DeflaterOutputStream(bytes, deflater)) {
            out.write(raw);
        } finally {
            deflater.end();
        }
        return bytes.toByteArray();
    }

    private static int int24(final byte[] data, final int at) {
        return ((data[at] & 0xFF) << 16) | ((data[at + 1] & 0xFF) << 8) | (data[at + 2] & 0xFF);
    }

    private static int int32(final byte[] data, final int at) {
        return ((data[at] & 0xFF) << 24) | int24(data, at + 1);
    }

    private static void putInt24(final byte[] data, final int at, final int value) {
        data[at] = (byte) (value >> 16);
        data[at + 1] = (byte) (value >> 8);
        data[at + 2] = (byte) value;
    }

    private static void putInt32(final byte[] data, final int at, final int value) {
        data[at] = (byte) (value >> 24);
        putInt24(data, at + 1, value);
    }
}
