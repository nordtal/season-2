package eu.nordtal.season.smp.eye;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;

/** Writes region files as the game stores them, with chunks whose sections hold the blocks a test names. */
final class TestRegions {

    private TestRegions() {}

    /** Returns a chunk whose one section at section height {@code sectionY} holds frames at the block indices. */
    static Map<String, Object> chunk(final int sectionY, final int... frameIndices) {
        final List<Object> palette = new ArrayList<>();
        // As the game writes a list of mixed entries: a lone string under an empty key, a state as id and properties.
        palette.add(Map.of("", "minecraft:stone"));
        palette.add(Map.of("", "minecraft:air"));
        palette.add(Map.of("id", RegionFrames.FRAME, "properties", Map.of("eye", "false", "facing", "north")));
        final long[] data = new long[4096 / 16];
        for (final int index : frameIndices) {
            data[index / 16] |= 2L << ((index % 16) * 4);
        }
        final Map<String, Object> states = new LinkedHashMap<>();
        states.put("palette", palette);
        states.put("data", data);
        final Map<String, Object> section = new LinkedHashMap<>();
        section.put("Y", (byte) sectionY);
        section.put("block_states", states);
        final Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("DataVersion", 4000);
        chunk.put("Status", "minecraft:full");
        chunk.put("sections", List.of(section));
        return chunk;
    }

    /** Returns the block index of x, y and z inside a section. */
    static int index(final int x, final int y, final int z) {
        return (y & 15) << 8 | (z & 15) << 4 | (x & 15);
    }

    /** Writes the chunks, keyed by their index x + z * 32 inside the region, as a zlib region file. */
    static void write(final Path file, final Map<Integer, byte[]> chunks) throws IOException {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final byte[] header = new byte[8192];
        int sector = 2;
        for (final Map.Entry<Integer, byte[]> chunk : chunks.entrySet()) {
            final byte[] stored = chunk.getValue();
            final int length = stored.length + 1;
            final int sectors = (length + 4 + 4095) / 4096;
            final int at = chunk.getKey() * 4;
            header[at] = (byte) (sector >> 16);
            header[at + 1] = (byte) (sector >> 8);
            header[at + 2] = (byte) sector;
            header[at + 3] = (byte) sectors;
            final DataOutputStream lead = new DataOutputStream(body);
            lead.writeInt(length);
            lead.writeByte(2);
            body.write(stored);
            body.write(new byte[sectors * 4096 - length - 4]);
            sector += sectors;
        }
        Files.createDirectories(file.getParent());
        final ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(header);
        body.writeTo(all);
        Files.write(file, all.toByteArray());
    }

    /** Returns the chunk as zlib-compressed NBT. */
    static byte[] deflated(final Map<String, Object> chunk) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            out.writeByte(10);
            out.writeUTF("");
            compound(out, chunk);
        }
        return bytes.toByteArray();
    }

    private static void compound(final DataOutputStream out, final Map<?, ?> values) throws IOException {
        for (final Map.Entry<?, ?> entry : values.entrySet()) {
            out.writeByte(type(entry.getValue()));
            out.writeUTF((String) entry.getKey());
            value(out, entry.getValue());
        }
        out.writeByte(0);
    }

    private static int type(final Object value) {
        return switch (value) {
            case Byte b -> 1;
            case Integer i -> 3;
            case String s -> 8;
            case List<?> l -> 9;
            case Map<?, ?> m -> 10;
            case long[] a -> 12;
            default -> throw new IllegalArgumentException("No NBT tag for " + value.getClass());
        };
    }

    private static void value(final DataOutputStream out, final Object value) throws IOException {
        switch (value) {
            case Byte b -> out.writeByte(b);
            case Integer i -> out.writeInt(i);
            case String s -> out.writeUTF(s);
            case List<?> list -> {
                out.writeByte(list.isEmpty() ? 0 : type(list.getFirst()));
                out.writeInt(list.size());
                for (final Object element : list) {
                    value(out, element);
                }
            }
            case Map<?, ?> map -> compound(out, map);
            case long[] longs -> {
                out.writeInt(longs.length);
                for (final long l : longs) {
                    out.writeLong(l);
                }
            }
            default -> throw new IllegalArgumentException("No NBT tag for " + value.getClass());
        }
    }
}
