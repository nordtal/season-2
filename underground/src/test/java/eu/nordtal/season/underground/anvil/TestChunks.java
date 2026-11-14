package eu.nordtal.season.underground.anvil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds chunks as the game stores them, from a rule that names the block at each position. */
public final class TestChunks {

    private TestChunks() {}

    /** Names the block at a position inside a chunk, x and z from 0 to 15. */
    @FunctionalInterface
    public interface BlockAt {
        String name(int x, int y, int z);
    }

    public static Map<String, Object> chunk(final int chunkX, final int chunkZ, final BlockAt blocks) {
        final NbtList sections = new NbtList(Nbt.COMPOUND);
        for (int sectionY = ChunkBlocks.MIN_Y / 16;
                sectionY < (ChunkBlocks.MIN_Y + ChunkBlocks.HEIGHT) / 16;
                sectionY++) {
            final List<String> palette = new ArrayList<>();
            final int[] indices = new int[Section.BLOCKS];
            for (int i = 0; i < Section.BLOCKS; i++) {
                final String name = blocks.name(i & 15, sectionY * 16 + (i >> 8), i >> 4 & 15);
                if (!palette.contains(name)) {
                    palette.add(name);
                }
                indices[i] = palette.indexOf(name);
            }
            final NbtList names = new NbtList(Nbt.STRING);
            names.addAll(palette);
            final Map<String, Object> blockStates = new LinkedHashMap<>();
            final long[] data = Section.pack(indices, palette.size(), 4);
            if (data != null) {
                blockStates.put("data", data);
            }
            blockStates.put("palette", names);
            final NbtList biome = new NbtList(Nbt.STRING);
            biome.add("minecraft:plains");
            final Map<String, Object> section = new LinkedHashMap<>();
            section.put("Y", (byte) sectionY);
            section.put("block_states", blockStates);
            section.put("biomes", new LinkedHashMap<>(Map.of("palette", biome)));
            section.put("SkyLight", new byte[2048]);
            sections.add(section);
        }
        final Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("DataVersion", 5023);
        chunk.put("Status", "minecraft:full");
        chunk.put("xPos", chunkX);
        chunk.put("zPos", chunkZ);
        chunk.put("isLightOn", (byte) 1);
        chunk.put("sections", sections);
        chunk.put("block_entities", new NbtList(Nbt.COMPOUND));
        return chunk;
    }
}
