package eu.nordtal.season.underground.structures;

import eu.nordtal.season.underground.anvil.ChunkBlocks;
import eu.nordtal.season.underground.anvil.Region;
import eu.nordtal.season.underground.anvil.Section;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Reads the blocks and biomes of an area of a world, one region file read per area. */
public final class Area {

    private static final String AIR = "minecraft:air";

    private final Path regions;
    private final Map<Long, ChunkBlocks> chunks = new HashMap<>();

    public Area(final Path world) {
        this.regions = world.resolve("region");
    }

    /** Loads the chunks a box touches, grown by a margin, and forgets the area loaded before. */
    public void load(final Box box, final int margin) throws IOException {
        chunks.clear();
        final Box area = box.grow(margin, 0, 0);
        final Map<Long, Set<Integer>> wanted = new HashMap<>();
        for (int chunkX = area.minChunkX(); chunkX <= area.maxChunkX(); chunkX++) {
            for (int chunkZ = area.minChunkZ(); chunkZ <= area.maxChunkZ(); chunkZ++) {
                wanted.computeIfAbsent(Finds.key(chunkX >> 5, chunkZ >> 5), k -> new HashSet<>())
                        .add((chunkX & 31) + (chunkZ & 31) * Region.SIDE);
            }
        }
        for (final Map.Entry<Long, Set<Integer>> region : wanted.entrySet()) {
            final int regionX = (int) (region.getKey() >> 32);
            final int regionZ = (int) (long) region.getKey();
            Region.each(Region.file(regions, regionX, regionZ), region.getValue()::contains, (index, chunk) -> {
                if (ChunkBlocks.full(chunk)) {
                    chunks.put(
                            Finds.key(
                                    regionX * Region.SIDE + index % Region.SIDE,
                                    regionZ * Region.SIDE + index / Region.SIDE),
                            new ChunkBlocks(chunk));
                }
            });
        }
    }

    /** Returns the palette entry at a position, air outside the loaded chunks. */
    public Object entry(final int x, final int y, final int z) {
        final ChunkBlocks chunk = chunks.get(Finds.key(x >> 4, z >> 4));
        return chunk == null ? AIR : chunk.entry(x & 15, y, z & 15);
    }

    /** Returns the block name at a position without its namespace, such as stone_bricks. */
    public String name(final int x, final int y, final int z) {
        final String name = Section.name(entry(x, y, z));
        return name.substring(name.indexOf(':') + 1);
    }

    /** Returns the biome at a position, or null outside the loaded chunks. */
    public @Nullable String biome(final int x, final int y, final int z) {
        final ChunkBlocks chunk = chunks.get(Finds.key(x >> 4, z >> 4));
        return chunk == null ? null : chunk.biome(x & 15, y, z & 15);
    }
}
