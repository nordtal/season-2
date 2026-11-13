package eu.nordtal.season.underground.check;

import eu.nordtal.season.underground.anvil.ChunkBlocks;
import eu.nordtal.season.underground.anvil.Nbt;
import eu.nordtal.season.underground.anvil.Region;
import eu.nordtal.season.underground.anvil.Section;
import eu.nordtal.season.underground.block.Blocks;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** One region of the map, the generated world and the result: a byte of facts per cell at (y * 512 + z) * 512 + x. */
final class Compared {

    /** The cell of the result is open. */
    static final int OPEN = 1;

    /** The cell of the result is water or lava. */
    static final int FLUID = 2;

    /** The cell of the map was open. */
    static final int MAP_OPEN = 4;

    /** The cell of the generated world is open. */
    static final int PARALLEL_OPEN = 8;

    /** The result holds the block state of the map. */
    static final int AS_MAP = 16;

    /** The result holds the block state of the generated world. */
    static final int AS_PARALLEL = 32;

    /** The block of the map is on no list, so somebody placed it. */
    static final int BUILT = 64;

    /** The block of the map is on the list of blocks that are ground or building. */
    static final int AMBIGUOUS = 128;

    static final int SIDE = 512;

    final byte[] cells = new byte[SIDE * SIDE * ChunkBlocks.HEIGHT];
    private final Blocks blocks = Blocks.load();
    private final Map<String, Integer> states = new HashMap<>();

    Compared(final Path map, final Path parallel, final Path out, final int regionX, final int regionZ)
            throws IOException {
        final Map<Integer, Map<String, Object>> before =
                Region.read(Region.file(map.resolve("region"), regionX, regionZ));
        final Map<Integer, Map<String, Object>> generated =
                Region.read(Region.file(parallel.resolve("region"), regionX, regionZ));
        final Map<Integer, Map<String, Object>> after =
                Region.read(Region.file(out.resolve("region"), regionX, regionZ));
        for (final Map.Entry<Integer, Map<String, Object>> chunk : after.entrySet()) {
            final Map<String, Object> first = before.get(chunk.getKey());
            final Map<String, Object> second = generated.get(chunk.getKey());
            if (first != null && second != null && full(chunk.getValue(), first, second)) {
                chunk(
                        new ChunkBlocks(first),
                        new ChunkBlocks(second),
                        new ChunkBlocks(chunk.getValue()),
                        chunk.getKey() % Region.SIDE * 16,
                        chunk.getKey() / Region.SIDE * 16);
            }
        }
    }

    private static boolean full(
            final Map<String, Object> out, final Map<String, Object> map, final Map<String, Object> parallel) {
        return ChunkBlocks.full(out) && ChunkBlocks.full(map) && ChunkBlocks.full(parallel);
    }

    static int index(final int x, final int layer, final int z) {
        return (layer * SIDE + z) * SIDE + x;
    }

    private void chunk(
            final ChunkBlocks map,
            final ChunkBlocks parallel,
            final ChunkBlocks out,
            final int baseX,
            final int baseZ) {
        for (int section = 0; section < ChunkBlocks.HEIGHT / 16; section++) {
            final int[] mapStates = states(map, section);
            final int[] parallelStates = states(parallel, section);
            final int[] outStates = states(out, section);
            final int[] mapKinds = kinds(map, section, true);
            final int[] parallelKinds = kinds(parallel, section, false);
            final int[] outKinds = kinds(out, section, false);
            final int[] mapAt = map.indices(section);
            final int[] parallelAt = parallel.indices(section);
            final int[] outAt = out.indices(section);
            for (int i = 0; i < Section.BLOCKS; i++) {
                final int mapIndex = mapAt == null ? 0 : mapAt[i];
                final int parallelIndex = parallelAt == null ? 0 : parallelAt[i];
                final int outIndex = outAt == null ? 0 : outAt[i];
                final int was = mapKinds[mapIndex] & Blocks.KIND;
                // A block the result has from the map is what it was there: a fence is built, not a plant.
                final boolean asMap = outStates[outIndex] == mapStates[mapIndex];
                final int kind = asMap ? was : outKinds[outIndex] & Blocks.KIND;
                int facts = Blocks.open(kind) ? OPEN : 0;
                facts |= kind == Blocks.WATER || kind == Blocks.LAVA ? FLUID : 0;
                facts |= Blocks.open(was) ? MAP_OPEN : 0;
                facts |= Blocks.open(parallelKinds[parallelIndex]) ? PARALLEL_OPEN : 0;
                facts |= asMap ? AS_MAP : 0;
                facts |= outStates[outIndex] == parallelStates[parallelIndex] ? AS_PARALLEL : 0;
                facts |= was == Blocks.BUILT ? BUILT : was == Blocks.AMBIGUOUS ? AMBIGUOUS : 0;
                cells[index(baseX + (i & 15), section * 16 + (i >> 8), baseZ + (i >> 4 & 15))] = (byte) facts;
            }
        }
    }

    /** Returns a number per palette entry that is equal for equal block states, across the three worlds. */
    private int[] states(final ChunkBlocks chunk, final int section) {
        final Object[] palette = chunk.palette(section);
        if (palette == null) {
            return new int[] {states.computeIfAbsent("minecraft:air", _ -> states.size())};
        }
        final int[] ids = new int[palette.length];
        for (int i = 0; i < palette.length; i++) {
            final String key = Section.key(Nbt.unwrap(palette[i]));
            ids[i] = states.computeIfAbsent(key.endsWith(":cave_air") ? "minecraft:air" : key, _ -> states.size());
        }
        return ids;
    }

    private int[] kinds(final ChunkBlocks chunk, final int section, final boolean map) {
        final Object[] palette = chunk.palette(section);
        if (palette == null) {
            return new int[] {Blocks.AIR};
        }
        final int[] kinds = new int[palette.length];
        for (int i = 0; i < palette.length; i++) {
            final String name = Section.name(palette[i]);
            kinds[i] = map ? blocks.mapCell(name) : blocks.parallelCell(name);
        }
        return kinds;
    }
}
