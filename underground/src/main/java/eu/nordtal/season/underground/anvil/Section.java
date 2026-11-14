package eu.nordtal.season.underground.anvil;

import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** The paletted containers of a chunk section: blocks by y * 256 + z * 16 + x, biomes by y * 16 + z * 4 + x. */
public final class Section {

    /** The number of blocks in one section. */
    public static final int BLOCKS = 4096;

    /** The number of biome cells in one section. */
    public static final int BIOMES = 64;

    private Section() {}

    /** Returns the sections of a chunk by their section Y. */
    public static Map<Integer, Map<String, Object>> of(final Map<String, Object> chunk) {
        final Map<Integer, Map<String, Object>> sections = new TreeMap<>();
        for (final Object section : Nbt.list(chunk, "sections")) {
            final Map<String, Object> compound = Nbt.compound(section);
            sections.put(Nbt.number(compound, "Y", 0), compound);
        }
        return sections;
    }

    /** Returns the block name of a palette entry, in the format before and after the palette became strings. */
    public static String name(final Object entry) {
        if (entry instanceof String text) {
            return text;
        }
        final Map<String, Object> compound = Nbt.compound(entry);
        for (final String key : new String[] {"id", "Name", ""}) {
            final Object name = compound.get(key);
            if (name != null) {
                return String.valueOf(name);
            }
        }
        return "minecraft:air";
    }

    /** Returns a text that is equal for two palette entries exactly when they are the same block state. */
    public static String key(final Object entry) {
        if (entry instanceof String text) {
            return text;
        }
        final Map<String, Object> compound = Nbt.compound(entry);
        Object properties = compound.get("properties");
        if (properties == null) {
            properties = compound.get("Properties");
        }
        return properties == null ? name(entry) : name(entry) + new TreeMap<>(Nbt.compound(properties));
    }

    /** Returns the palette index of every cell of a container with this many cells and this smallest width. */
    public static int[] indices(final Map<String, Object> container, final int cells, final int minimumBits) {
        final int[] indices = new int[cells];
        final int size = Nbt.list(container, "palette").size();
        final Object data = container.get("data");
        if (!(data instanceof long[] packed) || size <= 1) {
            return indices;
        }
        final int bits = Math.max(minimumBits, 32 - Integer.numberOfLeadingZeros(size - 1));
        final int perLong = 64 / bits;
        final long mask = (1L << bits) - 1;
        for (int i = 0; i < cells; i++) {
            indices[i] = (int) ((packed[i / perLong] >>> ((i % perLong) * bits)) & mask);
        }
        return indices;
    }

    /** Packs palette indices, or returns null for a palette of one entry, which is stored without data. */
    public static long @Nullable [] pack(final int[] indices, final int paletteSize, final int minimumBits) {
        if (paletteSize <= 1) {
            return null;
        }
        final int bits = Math.max(minimumBits, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
        final int perLong = 64 / bits;
        final long[] packed = new long[(indices.length + perLong - 1) / perLong];
        for (int i = 0; i < indices.length; i++) {
            packed[i / perLong] |= ((long) indices[i]) << ((i % perLong) * bits);
        }
        return packed;
    }

    /** Returns the palette indices of a section's blocks. */
    public static int[] blockIndices(final Map<String, Object> blockStates) {
        return indices(blockStates, BLOCKS, 4);
    }

    /** Returns the palette indices of a section's biome cells. */
    public static int[] biomeIndices(final Map<String, Object> biomes) {
        return indices(biomes, BIOMES, 1);
    }
}
