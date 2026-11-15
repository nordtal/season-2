package eu.nordtal.season.underground.structures;

import eu.nordtal.season.underground.anvil.ChunkBlocks;
import eu.nordtal.season.underground.anvil.Nbt;
import eu.nordtal.season.underground.anvil.Section;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/** Gives the suspicious sand and gravel of the map the game's archaeology tables; the module's README has the rules. */
final class Archaeology {

    static final String WARM = "minecraft:archaeology/ocean_ruin_warm";
    static final String COLD = "minecraft:archaeology/ocean_ruin_cold";
    static final String PYRAMID = "minecraft:archaeology/desert_pyramid";
    static final String WELL = "minecraft:archaeology/desert_well";
    static final String TRAIL = "minecraft:archaeology/trail_ruins_common";
    static final String TRAIL_RARE = "minecraft:archaeology/trail_ruins_rare";

    /** The fewest suspicious blocks of a place whose dry sand rolls the desert pyramid's table, not the well's. */
    static final int LARGE = 1000;

    /** One in this many blocks of dry gravel rolls the rare table of the trail ruins. */
    static final int RARE = 8;

    private static final String SAND = "minecraft:suspicious_sand";
    private static final String GRAVEL = "minecraft:suspicious_gravel";
    private static final String BRUSHABLE = "minecraft:brushable_block";
    private static final Set<String> WATER =
            Set.of("water", "seagrass", "tall_seagrass", "kelp", "kelp_plant", "bubble_column");
    private static final Set<String> AIR = Set.of("air", "cave_air", "void_air");

    private Archaeology() {}

    /** Returns, for every chunk that holds suspicious blocks, how many its place holds: chunks touching join. */
    static Map<Long, Integer> places(final List<Finds.Point> blocks) {
        final Map<Long, Integer> counts = new HashMap<>();
        for (final Finds.Point block : blocks) {
            counts.merge(Finds.key(block.x() >> 4, block.z() >> 4), 1, Integer::sum);
        }
        final Map<Long, Integer> sizes = new TreeMap<>();
        for (final long first : counts.keySet()) {
            if (sizes.containsKey(first)) {
                continue;
            }
            final List<Long> place = new ArrayList<>();
            final Deque<Long> next = new ArrayDeque<>(List.of(first));
            sizes.put(first, 0);
            int size = 0;
            while (!next.isEmpty()) {
                final long chunk = next.pop();
                place.add(chunk);
                size += counts.getOrDefault(chunk, 0);
                final int chunkX = (int) (chunk >> 32);
                final int chunkZ = (int) chunk;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        final long beside = Finds.key(chunkX + dx, chunkZ + dz);
                        if (counts.containsKey(beside) && !sizes.containsKey(beside)) {
                            sizes.put(beside, 0);
                            next.push(beside);
                        }
                    }
                }
            }
            for (final long chunk : place) {
                sizes.put(chunk, size);
            }
        }
        return sizes;
    }

    /** Returns the table of a suspicious sand or gravel block. */
    static String table(final String block, final boolean wet, final boolean large, final boolean rare) {
        if (block.equals(SAND)) {
            return wet ? WARM : large ? PYRAMID : WELL;
        }
        if (block.equals(GRAVEL)) {
            return wet ? COLD : rare ? TRAIL_RARE : TRAIL;
        }
        throw new IllegalArgumentException(block + " is no suspicious block");
    }

    /** Returns whether water lies above a position before open air does, so the block lies under water. */
    static boolean wet(final ChunkBlocks blocks, final int x, final int y, final int z) {
        for (int up = y + 1; up < ChunkBlocks.MIN_Y + ChunkBlocks.HEIGHT; up++) {
            final String name = Section.name(blocks.entry(x & 15, up, z & 15));
            final String plain = name.substring(name.indexOf(':') + 1);
            if (WATER.contains(plain)) {
                return true;
            }
            if (AIR.contains(plain)) {
                return false;
            }
        }
        return false;
    }

    /**
     * Sets a table on every suspicious block of a chunk that rolls none and holds no item, counting them per table.
     * Returns whether it set any.
     */
    static boolean dig(
            final Map<String, Object> chunk, final boolean large, final Random random, final Map<String, Integer> set) {
        final ChunkBlocks blocks = new ChunkBlocks(chunk);
        boolean changed = false;
        for (final Object element : Nbt.list(chunk, "block_entities")) {
            final Map<String, Object> entity = Nbt.compound(element);
            if (!BRUSHABLE.equals(entity.get("id")) || entity.get("LootTable") instanceof String || holds(entity)) {
                continue;
            }
            final int x = Nbt.number(entity, "x", 0);
            final int y = Nbt.number(entity, "y", 0);
            final int z = Nbt.number(entity, "z", 0);
            final String block = Section.name(blocks.entry(x & 15, y, z & 15));
            if (!block.equals(SAND) && !block.equals(GRAVEL)) {
                continue;
            }
            final String table = table(block, wet(blocks, x, y, z), large, random.nextInt(RARE) == 0);
            entity.remove("item");
            entity.put("LootTable", table);
            entity.put("LootTableSeed", random.nextLong());
            set.merge(table, 1, Integer::sum);
            changed = true;
        }
        return changed;
    }

    /** Returns whether a suspicious block holds an item a player put there or the map gave it. */
    private static boolean holds(final Map<String, Object> entity) {
        final Map<String, Object> item = Nbt.child(entity, "item");
        return item != null && item.get("id") instanceof String id && !id.equals("minecraft:air");
    }
}
