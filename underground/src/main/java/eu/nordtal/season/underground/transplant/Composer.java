package eu.nordtal.season.underground.transplant;

import eu.nordtal.season.underground.anvil.ChunkBlocks;
import eu.nordtal.season.underground.anvil.Nbt;
import eu.nordtal.season.underground.anvil.NbtList;
import eu.nordtal.season.underground.anvil.Section;
import eu.nordtal.season.underground.block.Blocks;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Writes the decisions of a tile into the chunks of the map: blocks, biomes, block entities, structures and light. */
final class Composer {

    private static final Set<String> CAVE_BIOMES = Set.of(
            "minecraft:lush_caves", "minecraft:dripstone_caves", "minecraft:deep_dark", "minecraft:sulfur_caves");
    private static final String CAVE_AIR = "minecraft:cave_air";
    static final int MAP = 0;
    static final int PARALLEL = 1;
    static final int NEW = 2;

    private final Tile tile;
    private final Tally tally;
    private final int[] faces;

    Composer(final Tile tile, final Tally tally) {
        this.tile = tile;
        this.tally = tally;
        faces = tile.faces();
    }

    /** Changes a chunk of the map in place into the chunk of the result. */
    void compose(
            final Map<String, Object> map, final Map<String, Object> parallel, final int chunkX, final int chunkZ) {
        final int baseX = chunkX * 16 - tile.originX;
        final int baseZ = chunkZ * 16 - tile.originZ;
        final ChunkBlocks mapBlocks = new ChunkBlocks(map);
        final ChunkBlocks parallelBlocks = new ChunkBlocks(parallel);
        final Map<Integer, Map<String, Object>> sections = Section.of(map);
        final Map<Integer, Map<String, Object>> parallelSections = Section.of(parallel);
        for (int at = 0; at < ChunkBlocks.HEIGHT / 16; at++) {
            final int sectionY = at + ChunkBlocks.MIN_Y / 16;
            final Map<String, Object> section = sections.get(sectionY);
            final Map<String, Object> other = parallelSections.get(sectionY);
            if (section == null || other == null || untouched(baseX, at, baseZ)) {
                continue;
            }
            section.put("block_states", blocks(mapBlocks, parallelBlocks, baseX, at, baseZ));
            section.put("biomes", biomes(mapBlocks, parallelBlocks, baseX, at, baseZ));
        }
        for (final Map<String, Object> section : sections.values()) {
            section.keySet().removeIf(key -> key.endsWith("Light") || key.startsWith("starlight."));
        }
        map.keySet().removeIf(key -> key.startsWith("starlight."));
        map.put("isLightOn", (byte) 0);
        map.put("Heightmaps", new LinkedHashMap<String, Object>());
        for (final String key : new String[] {"block_entities", "block_ticks", "fluid_ticks"}) {
            map.put(key, placed(Nbt.list(map, key), Nbt.list(parallel, key), baseX, baseZ));
        }
        structures(map, parallel);
    }

    /** Returns which world a cell of the result comes from: the map, the generated world, or neither. */
    int origin(final int cell) {
        final int flags = tile.flags[cell];
        final boolean kept = (flags & Tile.KEPT) != 0;
        final boolean rock = (flags & (kept ? Tile.FILLED : Tile.CLOSED)) != 0;
        final boolean open = Blocks.open(kept ? tile.map[cell] : tile.parallel[cell]);
        if (rock || ((flags & Tile.CARVED) != 0 && !open)) {
            return NEW;
        }
        return kept ? MAP : PARALLEL;
    }

    private boolean untouched(final int baseX, final int section, final int baseZ) {
        for (int i = 0; i < Section.BLOCKS; i++) {
            if (origin(tile.index(baseX + (i & 15), section * 16 + (i >> 8), baseZ + (i >> 4 & 15))) != MAP) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Object> blocks(
            final ChunkBlocks map, final ChunkBlocks parallel, final int baseX, final int section, final int baseZ) {
        final List<Object> palette = new ArrayList<>();
        final Map<String, Integer> known = new HashMap<>();
        final int[] indices = new int[Section.BLOCKS];
        for (int i = 0; i < Section.BLOCKS; i++) {
            final int x = i & 15;
            final int z = i >> 4 & 15;
            final int y = section * 16 + (i >> 8) + ChunkBlocks.MIN_Y;
            final int cell = tile.index(baseX + x, section * 16 + (i >> 8), baseZ + z);
            final Object entry = switch (origin(cell)) {
                case MAP -> Nbt.unwrap(map.entry(x, y, z));
                case PARALLEL -> Nbt.unwrap(parallel.entry(x, y, z));
                default -> fresh(cell, y);
            };
            final String key = Section.key(entry);
            Integer index = known.get(key);
            if (index == null) {
                index = palette.size();
                palette.add(entry);
                known.put(key, index);
            }
            indices[i] = index;
        }
        final boolean compounds = palette.stream().anyMatch(entry -> entry instanceof Map);
        final NbtList list = new NbtList(compounds ? Nbt.COMPOUND : Nbt.STRING);
        for (final Object entry : palette) {
            list.add(compounds && entry instanceof String ? new LinkedHashMap<>(Map.of("", entry)) : entry);
        }
        final Map<String, Object> blockStates = new LinkedHashMap<>();
        final long[] data = Section.pack(indices, palette.size(), 4);
        if (data != null) {
            blockStates.put("data", data);
        }
        blockStates.put("palette", list);
        return blockStates;
    }

    /** Returns the block of a cell that comes from neither world: air in an entrance, else the rock nearby. */
    private String fresh(final int cell, final int y) {
        final int flags = tile.flags[cell];
        if ((flags & Tile.CARVED) != 0) {
            tally.carved++;
            return CAVE_AIR;
        }
        final boolean kept = (flags & Tile.KEPT) != 0;
        if (kept) {
            tally.filled++;
        } else {
            tally.closed++;
        }
        final byte[] layer = kept ? tile.map : tile.parallel;
        if (!tile.edge(cell)) {
            for (int far = 1; far <= 6; far++) {
                for (final int face : faces) {
                    final int near = cell + far * face;
                    if (near >= 0 && near < layer.length && Blocks.fills(layer[near])) {
                        return Blocks.filling(layer[near], y);
                    }
                }
            }
        }
        return Blocks.filling(0, y);
    }

    private Map<String, Object> biomes(
            final ChunkBlocks map, final ChunkBlocks parallel, final int baseX, final int section, final int baseZ) {
        final List<String> palette = new ArrayList<>();
        final int[] indices = new int[Section.BIOMES];
        for (int i = 0; i < Section.BIOMES; i++) {
            final int x = (i & 3) * 4 + 2;
            final int z = (i >> 2 & 3) * 4 + 2;
            final int layer = section * 16 + (i >> 4) * 4 + 2;
            final int y = layer + ChunkBlocks.MIN_Y;
            String biome = map.biome(x, y, z);
            if (!tile.has(tile.index(baseX + x, layer, baseZ + z), Tile.KEPT)) {
                final String generated = parallel.biome(x, y, z);
                biome = generated != null && CAVE_BIOMES.contains(generated) ? generated : surfaceBiome(map, x, z);
            }
            final String name = biome == null ? "minecraft:plains" : biome;
            if (!palette.contains(name)) {
                palette.add(name);
            }
            indices[i] = palette.indexOf(name);
        }
        final NbtList list = new NbtList(Nbt.STRING);
        list.addAll(palette);
        final Map<String, Object> biomes = new LinkedHashMap<>();
        final long[] data = Section.pack(indices, palette.size(), 1);
        if (data != null) {
            biomes.put("data", data);
        }
        biomes.put("palette", list);
        return biomes;
    }

    /** Returns the biome of the map above the ground of a column, which is the biome a player there is in. */
    private static @Nullable String surfaceBiome(final ChunkBlocks map, final int x, final int z) {
        for (int y = ChunkBlocks.MIN_Y + ChunkBlocks.HEIGHT - 1; y >= ChunkBlocks.MIN_Y; y -= 4) {
            final String biome = map.biome(x, y, z);
            if (biome != null && !CAVE_BIOMES.contains(biome)) {
                return biome;
            }
        }
        return null;
    }

    /** Returns the entries with a position, such as block entities, whose cell comes from their own world. */
    private NbtList placed(final NbtList map, final NbtList parallel, final int baseX, final int baseZ) {
        final NbtList merged = new NbtList(Nbt.COMPOUND);
        for (int world = MAP; world <= PARALLEL; world++) {
            for (final Object entry : world == MAP ? map : parallel) {
                final Map<String, Object> compound = Nbt.compound(entry);
                final int layer = Nbt.number(compound, "y", 0) - ChunkBlocks.MIN_Y;
                final int x = baseX + (Nbt.number(compound, "x", 0) & 15);
                final int z = baseZ + (Nbt.number(compound, "z", 0) & 15);
                final boolean inside = layer >= 0 && layer < tile.height;
                if (inside ? origin(tile.index(x, layer, z)) == world : world == MAP) {
                    merged.add(compound);
                    tally.placed[world]++;
                }
            }
        }
        return merged;
    }

    private static void structures(final Map<String, Object> map, final Map<String, Object> parallel) {
        final Map<String, Object> generated = Nbt.child(parallel, "structures");
        if (generated == null) {
            return;
        }
        Map<String, Object> structures = Nbt.child(map, "structures");
        if (structures == null) {
            structures = new LinkedHashMap<>();
            map.put("structures", structures);
        }
        for (final String key : new String[] {"starts", "References"}) {
            final Map<String, Object> more = Nbt.child(generated, key);
            Map<String, Object> own = Nbt.child(structures, key);
            if (own == null) {
                own = new LinkedHashMap<>();
                structures.put(key, own);
            }
            if (more != null) {
                own.putAll(more);
            }
        }
    }
}
