package eu.nordtal.season.smp.eye;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import org.jspecify.annotations.Nullable;

/**
 * Finds every end portal frame stored in one Anvil region file.
 * Only a chunk whose bytes name the frame is parsed, so a region without one costs its inflation alone.
 */
final class RegionFrames {

    /** The frames of one region file, and whether every chunk of it could be read. */
    record Scanned(List<Frame> frames, boolean complete) {}

    static final String FRAME = "minecraft:end_portal_frame";

    private static final byte[] NEEDLE = FRAME.getBytes(StandardCharsets.UTF_8);
    private static final int SIDE = 32;
    private static final int SECTOR = 4096;
    private static final int EXTERNAL = 0x80;
    private static final int SECTION_BLOCKS = 4096;
    /** Far above any chunk the game writes; a larger one is read as broken instead of filling the heap. */
    private static final int MAX_CHUNK = 64 << 20;

    private final Inflater inflater = new Inflater();
    private byte[] buffer = new byte[1 << 20];

    /** Reads the region file; a chunk that cannot be read is skipped and marks the scan incomplete. */
    Scanned scan(final Path file) throws IOException {
        final int[] region = coordinates(file);
        if (region == null) {
            throw new IOException(file + " is not named like a region file.");
        }
        final byte[] data = Files.readAllBytes(file);
        final List<Frame> frames = new ArrayList<>();
        boolean complete = true;
        for (int index = 0; index < SIDE * SIDE && data.length >= 2 * SECTOR; index++) {
            final int offset = int24(data, index * 4) * SECTOR;
            if (offset == 0) {
                continue;
            }
            final int chunkX = region[0] * SIDE + index % SIDE;
            final int chunkZ = region[1] * SIDE + index / SIDE;
            try {
                final int length = chunk(file, data, offset, chunkX, chunkZ);
                if (contains(buffer, length, NEEDLE)) {
                    frames.addAll(frames(ChunkNbt.read(buffer, length), chunkX, chunkZ));
                }
            } catch (final IOException | DataFormatException | RuntimeException e) {
                // A chunk the server is writing right now reads torn; the next scan reads it whole.
                complete = false;
            }
        }
        return new Scanned(List.copyOf(frames), complete);
    }

    /** Releases the inflater. */
    void close() {
        inflater.end();
    }

    /** Inflates the chunk at {@code offset} into the buffer and returns its length. */
    private int chunk(final Path file, final byte[] data, final int offset, final int chunkX, final int chunkZ)
            throws IOException, DataFormatException {
        if (offset + 5 > data.length) {
            throw new IOException("The chunk's sector lies past the end of the file.");
        }
        final int length = int32(data, offset);
        final int compression = data[offset + 4] & 0xFF;
        if ((compression & EXTERNAL) != 0) {
            final Path external = file.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc");
            final byte[] stored = Files.readAllBytes(external);
            return inflate(stored, 0, stored.length, compression & ~EXTERNAL);
        }
        if (length < 1 || offset + 4 + length > data.length) {
            throw new IOException("The chunk's length runs past the end of the file.");
        }
        return inflate(data, offset + 5, length - 1, compression);
    }

    private int inflate(final byte[] stored, final int from, final int length, final int compression)
            throws IOException, DataFormatException {
        switch (compression) {
            case 1 -> {
                try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(stored, from, length))) {
                    final byte[] raw = in.readNBytes(MAX_CHUNK + 1);
                    ensure(raw.length);
                    System.arraycopy(raw, 0, buffer, 0, raw.length);
                    return raw.length;
                }
            }
            case 2 -> {
                inflater.reset();
                inflater.setInput(stored, from, length);
                int size = 0;
                while (!inflater.finished()) {
                    ensure(size + 1);
                    final int read = inflater.inflate(buffer, size, buffer.length - size);
                    if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                        throw new DataFormatException("The chunk's data ends early.");
                    }
                    size += read;
                }
                return size;
            }
            case 3 -> {
                ensure(length);
                System.arraycopy(stored, from, buffer, 0, length);
                return length;
            }
            default -> throw new IOException("Unknown chunk compression " + compression + ".");
        }
    }

    private void ensure(final int size) throws IOException {
        if (size > MAX_CHUNK) {
            throw new IOException("A chunk inflates to more than " + MAX_CHUNK + " bytes.");
        }
        if (size > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(size, buffer.length * 2));
        }
    }

    /** Returns the frames of one chunk's sections, read from their block palettes. */
    static List<Frame> frames(final Map<String, Object> chunk, final int chunkX, final int chunkZ) {
        final List<Frame> frames = new ArrayList<>();
        if (!(chunk.get("sections") instanceof List<?> sections)) {
            return frames;
        }
        for (final Object element : sections) {
            if (element instanceof Map<?, ?> section
                    && section.get("Y") instanceof Byte y
                    && section.get("block_states") instanceof Map<?, ?> states
                    && states.get("palette") instanceof List<?> palette) {
                final long[] data = states.get("data") instanceof long[] longs ? longs : new long[0];
                section(palette, data, chunkX * 16, y * 16, chunkZ * 16, frames);
            }
        }
        return frames;
    }

    private static void section(
            final List<?> palette,
            final long[] data,
            final int baseX,
            final int baseY,
            final int baseZ,
            final List<Frame> frames) {
        final boolean[] frame = new boolean[palette.size()];
        boolean any = false;
        for (int i = 0; i < frame.length; i++) {
            frame[i] = FRAME.equals(name(palette.get(i)));
            any |= frame[i];
        }
        if (!any) {
            return;
        }
        final int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        final int perLong = 64 / bits;
        final long mask = (1L << bits) - 1;
        for (int block = 0; block < SECTION_BLOCKS; block++) {
            final int id;
            if (palette.size() == 1) {
                id = 0;
            } else {
                final int at = block / perLong;
                if (at >= data.length) {
                    return;
                }
                id = (int) ((data[at] >>> ((block % perLong) * bits)) & mask);
            }
            if (id < frame.length && frame[id]) {
                frames.add(new Frame(baseX + (block & 15), baseY + (block >> 8), baseZ + ((block >> 4) & 15)));
            }
        }
    }

    /**
     * Returns the block name of a palette entry, in every form the game writes.
     * That is a string, a compound with {@code id} and {@code properties}, the same string wrapped under an empty key
     * in a list of mixed entries, or {@code Name} in the format before 26.1.
     */
    static @Nullable Object name(final Object entry) {
        if (!(entry instanceof Map<?, ?> state)) {
            return entry;
        }
        for (final String key : new String[] {"id", "Name", ""}) {
            final Object name = state.get(key);
            if (name != null) {
                return name;
            }
        }
        return null;
    }

    /** Returns the region coordinates in a file name such as r.3.-2.mca, or null for another name. */
    static int @Nullable [] coordinates(final Path file) {
        final String[] parts = file.getFileName().toString().split("\\.", -1);
        if (parts.length != 4 || !parts[0].equals("r") || !parts[3].equals("mca")) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    static boolean contains(final byte[] haystack, final int length, final byte[] needle) {
        final int last = length - needle.length;
        outer:
        for (int i = 0; i <= last; i++) {
            if (haystack[i] != needle[0]) {
                continue;
            }
            for (int j = 1; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static int int24(final byte[] data, final int at) {
        return ((data[at] & 0xFF) << 16) | ((data[at + 1] & 0xFF) << 8) | (data[at + 2] & 0xFF);
    }

    private static int int32(final byte[] data, final int at) {
        return ((data[at] & 0xFF) << 24) | int24(data, at + 1);
    }
}
