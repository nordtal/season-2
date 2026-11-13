package eu.nordtal.season.underground.anvil;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The blocks and biomes of one chunk, decoded once, for the build height of the overworld. */
public final class ChunkBlocks {

    /** The lowest block Y of the overworld. */
    public static final int MIN_Y = -64;

    /** The number of block layers of the overworld. */
    public static final int HEIGHT = 384;

    private static final int SECTIONS = HEIGHT / 16;
    private static final String AIR = "minecraft:air";

    private final Object[] @Nullable [] palettes = new Object[SECTIONS][];
    private final int[] @Nullable [] indices = new int[SECTIONS][];
    private final String[] @Nullable [] biomePalettes = new String[SECTIONS][];
    private final int[] @Nullable [] biomeIndices = new int[SECTIONS][];

    public ChunkBlocks(final Map<String, Object> chunk) {
        for (final Map.Entry<Integer, Map<String, Object>> section :
                Section.of(chunk).entrySet()) {
            final int at = section.getKey() - MIN_Y / 16;
            if (at < 0 || at >= SECTIONS) {
                continue;
            }
            final Map<String, Object> blockStates = Nbt.child(section.getValue(), "block_states");
            if (blockStates != null) {
                palettes[at] = Nbt.list(blockStates, "palette").toArray();
                indices[at] = Section.blockIndices(blockStates);
            }
            final Map<String, Object> biomes = Nbt.child(section.getValue(), "biomes");
            if (biomes != null) {
                biomePalettes[at] = Nbt.list(biomes, "palette").stream()
                        .map(String::valueOf)
                        .toArray(String[]::new);
                biomeIndices[at] = Section.biomeIndices(biomes);
            }
        }
    }

    /** Returns whether a chunk is generated to the end, the only kind of chunk the tool reads blocks from. */
    public static boolean full(final @Nullable Map<String, Object> chunk) {
        return chunk != null && Nbt.text(chunk, "Status").endsWith("full");
    }

    /** Returns the palette of the section with this index from the bottom, or null where the chunk has none. */
    public Object @Nullable [] palette(final int section) {
        return palettes[section];
    }

    /** Returns the palette indices of the section with this index from the bottom, or null. */
    public int @Nullable [] indices(final int section) {
        return indices[section];
    }

    /** Returns the palette entry at a position inside the chunk, x and z from 0 to 15. */
    public Object entry(final int x, final int y, final int z) {
        final int up = y - MIN_Y;
        final Object[] palette = up < 0 || up >= HEIGHT ? null : palettes[up >> 4];
        final int[] index = up < 0 || up >= HEIGHT ? null : indices[up >> 4];
        return palette == null || index == null ? AIR : palette[index[((up & 15) << 8) | (z << 4) | x]];
    }

    /** Returns the biome at a position inside the chunk, or null where the chunk stores none. */
    public @Nullable String biome(final int x, final int y, final int z) {
        final int up = Math.clamp(y - MIN_Y, 0, HEIGHT - 1);
        final String[] palette = biomePalettes[up >> 4];
        final int[] index = biomeIndices[up >> 4];
        return palette == null || index == null
                ? null
                : palette[index[(((up & 15) >> 2) << 4) | ((z >> 2) << 2) | (x >> 2)]];
    }
}
