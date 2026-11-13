package eu.nordtal.season.underground.transplant;

import eu.nordtal.season.underground.anvil.ChunkBlocks;
import eu.nordtal.season.underground.anvil.Nbt;
import eu.nordtal.season.underground.anvil.NbtList;
import eu.nordtal.season.underground.anvil.Region;
import eu.nordtal.season.underground.anvil.Section;
import eu.nordtal.season.underground.block.Blocks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Puts the underground of a generated world below the map, region by region, into a third world. */
public final class Transplant {

    private static final String MINECART = "minecraft:chest_minecart";

    private final Path map;
    private final Path parallel;
    private final Path out;
    private final Parameters measures;
    private final Blocks blocks = Blocks.load();
    private final Tally tally = new Tally();
    private final Map<Long, List<int[]>> beyond = new HashMap<>();

    public Transplant(final Path map, final Path parallel, final Path out, final Parameters measures) {
        this.map = map;
        this.parallel = parallel;
        this.out = out;
        this.measures = measures;
    }

    /** Writes the regions of the result and returns what changed. */
    public Tally run(final List<int[]> regions) throws IOException {
        for (final int[] region : regions) {
            if (Files.exists(Region.file(map.resolve("region"), region[0], region[1]))) {
                final long start = System.nanoTime();
                final long[] before = tally.counts();
                region(region[0], region[1]);
                System.out.println("region transplanted: x=" + region[0] + " z=" + region[1] + " seconds="
                        + (System.nanoTime() - start) / 1_000_000_000 + " " + tally.since(before));
            }
        }
        for (final Map.Entry<Long, List<int[]>> cells : beyond.entrySet()) {
            final Path file = Region.file(
                    out.resolve("region"),
                    (int) (cells.getKey() >> 32),
                    cells.getKey().intValue());
            if (Files.exists(file)) {
                final Map<Integer, Map<String, Object>> chunks = Region.read(file);
                cells.getValue().forEach(cell -> open(chunks, cell));
                Region.write(file, chunks);
            }
        }
        return tally;
    }

    private void region(final int regionX, final int regionZ) throws IOException {
        final TileLoader.Loaded loaded = new TileLoader(map, parallel, blocks).load(regionX, regionZ);
        final Tile tile = loaded.tile();
        final Entrances entrances = new Entrances(tile, measures, tally);
        new Zones(tile, measures, entrances).decide();
        final Composer composer = new Composer(tile, tally);
        final Map<Integer, Map<String, Object>> entities =
                Region.read(Region.file(map.resolve("entities"), regionX, regionZ));
        final Map<Integer, Map<String, Object>> generated =
                Region.read(Region.file(parallel.resolve("entities"), regionX, regionZ));
        for (final Map.Entry<Integer, Map<String, Object>> chunk : loaded.map().entrySet()) {
            final Map<String, Object> other = loaded.parallel().get(chunk.getKey());
            if (other == null) {
                continue;
            }
            final int version = Nbt.number(chunk.getValue(), "DataVersion", 0);
            if (version != Nbt.number(other, "DataVersion", 0)) {
                throw new IOException("The map and the generated world differ in their data version; upgrade the map.");
            }
            final int chunkX = regionX * Region.SIDE + chunk.getKey() % Region.SIDE;
            final int chunkZ = regionZ * Region.SIDE + chunk.getKey() / Region.SIDE;
            composer.compose(chunk.getValue(), other, chunkX, chunkZ);
            entities(composer, tile, entities, generated.get(chunk.getKey()), chunk.getKey(), chunkX, chunkZ, version);
            tally.chunks++;
        }
        final Map<Integer, Map<String, Object>> all = Region.read(
                Region.file(map.resolve("region"), regionX, regionZ),
                index -> !loaded.map().containsKey(index));
        all.putAll(loaded.map());
        Region.write(Region.file(out.resolve("region"), regionX, regionZ), all);
        if (!entities.isEmpty()) {
            Region.write(Region.file(out.resolve("entities"), regionX, regionZ), entities);
        }
        entrances.joined().forEach(System.out::println);
        for (final int[] cell : entrances.beyond()) {
            final long key = ((long) (cell[0] >> 9) << 32) | ((cell[2] >> 9) & 0xFFFFFFFFL);
            beyond.computeIfAbsent(key, _ -> new ArrayList<>()).add(cell);
        }
    }

    /** Drops the entities of the map that sat where the generated world is now, and takes over its chest minecarts. */
    private void entities(
            final Composer composer,
            final Tile tile,
            final Map<Integer, Map<String, Object>> entities,
            final @Nullable Map<String, Object> generated,
            final int index,
            final int chunkX,
            final int chunkZ,
            final int version) {
        final NbtList kept = new NbtList(Nbt.COMPOUND);
        final Map<String, Object> own = entities.get(index);
        for (int world = Composer.MAP; world <= Composer.PARALLEL; world++) {
            final Map<String, Object> source = world == Composer.MAP ? own : generated;
            for (final Object entity : source == null ? new NbtList(Nbt.COMPOUND) : Nbt.list(source, "Entities")) {
                final Map<String, Object> compound = Nbt.compound(entity);
                final NbtList position = Nbt.list(compound, "Pos");
                final int x = (int) Math.floor(((Number) position.get(0)).doubleValue()) - tile.originX;
                final int layer = (int) Math.floor(((Number) position.get(1)).doubleValue()) - ChunkBlocks.MIN_Y;
                final int z = (int) Math.floor(((Number) position.get(2)).doubleValue()) - tile.originZ;
                final boolean inside =
                        x >= 0 && z >= 0 && x < tile.width && z < tile.depth && layer >= 0 && layer < tile.height;
                final boolean minecart = MINECART.equals(compound.get("id"));
                if (world == Composer.MAP
                        ? !inside || composer.origin(tile.index(x, layer, z)) == Composer.MAP
                        : inside && minecart && composer.origin(tile.index(x, layer, z)) == Composer.PARALLEL) {
                    kept.add(compound);
                    tally.minecarts += world == Composer.PARALLEL ? 1 : 0;
                }
            }
        }
        if (own != null) {
            own.put("Entities", kept);
        } else if (!kept.isEmpty()) {
            final Map<String, Object> created = new LinkedHashMap<>();
            created.put("DataVersion", version);
            created.put("Position", new int[] {chunkX, chunkZ});
            created.put("Entities", kept);
            entities.put(index, created);
        }
    }

    /** Opens a cell of a written chunk for an entrance that crosses into this region, unless it is no ground. */
    private void open(final Map<Integer, Map<String, Object>> chunks, final int[] cell) {
        final Map<String, Object> chunk = chunks.get((cell[0] >> 4 & 31) + (cell[2] >> 4 & 31) * Region.SIDE);
        final Map<String, Object> section =
                chunk == null ? null : Section.of(chunk).get(cell[1] >> 4);
        final Map<String, Object> blockStates = section == null ? null : Nbt.child(section, "block_states");
        if (blockStates == null) {
            return;
        }
        final int at = ((cell[1] & 15) << 8) | ((cell[2] & 15) << 4) | (cell[0] & 15);
        final int[] indices = Section.blockIndices(blockStates);
        final NbtList palette = Nbt.list(blockStates, "palette");
        final String name = Section.name(palette.get(indices[at]));
        if ((blocks.parallelCell(name) & Blocks.KIND) != Blocks.ROCK) {
            return;
        }
        int air = -1;
        for (int i = 0; i < palette.size(); i++) {
            if (Section.name(palette.get(i)).equals("minecraft:cave_air")) {
                air = i;
            }
        }
        if (air < 0) {
            air = palette.size();
            final boolean compounds = palette.getFirst() instanceof Map;
            palette.add(compounds ? new LinkedHashMap<>(Map.of("", "minecraft:cave_air")) : "minecraft:cave_air");
        }
        indices[at] = air;
        blockStates.put("data", Section.pack(indices, palette.size(), 4));
        tally.carved++;
    }
}
