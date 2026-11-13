package eu.nordtal.season.underground.transplant;

import eu.nordtal.season.underground.anvil.ChunkBlocks;
import eu.nordtal.season.underground.anvil.Region;
import eu.nordtal.season.underground.anvil.Section;
import eu.nordtal.season.underground.block.Blocks;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Reads one region of the map and of the generated world, with a margin of chunks from the regions around it. */
final class TileLoader {

    /** The margin in chunks: wider than any distance a decision looks across. */
    static final int MARGIN = 4;

    private final Path mapWorld;
    private final Path parallelWorld;
    private final Blocks blocks;

    TileLoader(final Path mapWorld, final Path parallelWorld, final Blocks blocks) {
        this.mapWorld = mapWorld;
        this.parallelWorld = parallelWorld;
        this.blocks = blocks;
    }

    /** The tile of a region, and the chunks of the region itself by their index in the region file. */
    record Loaded(Tile tile, Map<Integer, Map<String, Object>> map, Map<Integer, Map<String, Object>> parallel) {}

    Loaded load(final int regionX, final int regionZ) throws IOException {
        final int side = (Region.SIDE + 2 * MARGIN) * 16;
        final Tile tile = new Tile(
                (regionX * Region.SIDE - MARGIN) * 16,
                (regionZ * Region.SIDE - MARGIN) * 16,
                side,
                side,
                ChunkBlocks.HEIGHT);
        final boolean[] present = new boolean[side * side];
        final Map<Integer, Map<String, Object>> map = new TreeMap<>();
        final Map<Integer, Map<String, Object>> parallel = new TreeMap<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                final boolean centre = dx == 0 && dz == 0;
                read(
                        mapWorld,
                        regionX + dx,
                        regionZ + dz,
                        tile,
                        tile.map,
                        blocks::mapCell,
                        present,
                        centre ? map : null);
            }
        }
        for (int i = 0; i < present.length; i++) {
            tile.missing[i] = !present[i];
            present[i] = false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                final boolean centre = dx == 0 && dz == 0;
                read(
                        parallelWorld,
                        regionX + dx,
                        regionZ + dz,
                        tile,
                        tile.parallel,
                        blocks::parallelCell,
                        present,
                        centre ? parallel : null);
            }
        }
        for (int i = 0; i < present.length; i++) {
            tile.missing[i] |= !present[i];
        }
        return new Loaded(tile, map, parallel);
    }

    private static void read(
            final Path world,
            final int regionX,
            final int regionZ,
            final Tile tile,
            final byte[] layer,
            final Function<String, Byte> cells,
            final boolean[] present,
            final @Nullable Map<Integer, Map<String, Object>> keep)
            throws IOException {
        final int firstChunkX = Math.floorDiv(tile.originX, 16);
        final int firstChunkZ = Math.floorDiv(tile.originZ, 16);
        final int chunks = tile.width / 16;
        final Map<Integer, Map<String, Object>> region =
                Region.read(Region.file(world.resolve("region"), regionX, regionZ), index -> {
                    final int x = regionX * Region.SIDE + index % Region.SIDE - firstChunkX;
                    final int z = regionZ * Region.SIDE + index / Region.SIDE - firstChunkZ;
                    return x >= 0 && z >= 0 && x < chunks && z < chunks;
                });
        for (final Map.Entry<Integer, Map<String, Object>> chunk : region.entrySet()) {
            if (!ChunkBlocks.full(chunk.getValue())) {
                continue;
            }
            final int x = (regionX * Region.SIDE + chunk.getKey() % Region.SIDE - firstChunkX) * 16;
            final int z = (regionZ * Region.SIDE + chunk.getKey() / Region.SIDE - firstChunkZ) * 16;
            place(tile, layer, new ChunkBlocks(chunk.getValue()), x, z, cells);
            for (int column = 0; column < 256; column++) {
                present[(z + (column >> 4)) * tile.width + x + (column & 15)] = true;
            }
            if (keep != null) {
                keep.put(chunk.getKey(), chunk.getValue());
            }
        }
    }

    static void place(
            final Tile tile,
            final byte[] layer,
            final ChunkBlocks chunk,
            final int baseX,
            final int baseZ,
            final Function<String, Byte> cells) {
        for (int section = 0; section < ChunkBlocks.HEIGHT / 16; section++) {
            final Object[] palette = chunk.palette(section);
            final int[] indices = chunk.indices(section);
            final byte[] kinds = new byte[palette == null ? 1 : palette.length];
            kinds[0] = (byte) Blocks.AIR;
            for (int i = 0; palette != null && i < palette.length; i++) {
                kinds[i] = cells.apply(Section.name(palette[i]));
            }
            for (int i = 0; i < Section.BLOCKS; i++) {
                final int cell = tile.index(baseX + (i & 15), section * 16 + (i >> 8), baseZ + (i >> 4 & 15));
                layer[cell] = kinds[palette == null || indices == null ? 0 : indices[i]];
            }
        }
    }
}
