package eu.nordtal.season.underground.structures;

import eu.nordtal.season.underground.anvil.ChunkBlocks;
import eu.nordtal.season.underground.anvil.Nbt;
import eu.nordtal.season.underground.anvil.Region;
import eu.nordtal.season.underground.anvil.Section;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Reads every chunk of a world once and collects what the plan needs. */
public final class Scan {

    private Scan() {}

    /** Scans the regions of a world in parallel. */
    public static Finds run(final Path world, final List<int[]> regions) {
        final Finds finds = new Finds();
        regions.parallelStream().forEach(region -> {
            try {
                finds.add(region(Region.file(world.resolve("region"), region[0], region[1]), region));
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return finds;
    }

    /** Scans one region file. */
    static Finds region(final Path file, final int[] region) throws IOException {
        final Finds finds = new Finds();
        Region.each(
                file,
                index -> true,
                (index, chunk) -> chunk(
                        finds,
                        region[0] * Region.SIDE + index % Region.SIDE,
                        region[1] * Region.SIDE + index / Region.SIDE,
                        chunk));
        return finds;
    }

    /** Scans one chunk. */
    static void chunk(final Finds finds, final int chunkX, final int chunkZ, final Map<String, Object> chunk) {
        final int version = Nbt.number(chunk, "DataVersion", -1);
        finds.versions.merge(version, 1, Integer::sum);
        finds.chunkVersions.put(Finds.key(chunkX, chunkZ), version);
        if (!ChunkBlocks.full(chunk)) {
            return;
        }
        finds.fullChunks.add(Finds.key(chunkX, chunkZ));
        for (final Object element : Nbt.list(chunk, "block_entities")) {
            blockEntity(finds, Nbt.compound(element));
        }
        blocks(finds, chunkX, chunkZ, chunk);
        starts(finds, chunkX, chunkZ, chunk);
    }

    private static void blockEntity(final Finds finds, final Map<String, Object> entity) {
        final String id = Nbt.text(entity, "id");
        final int x = Nbt.number(entity, "x", 0);
        final int y = Nbt.number(entity, "y", 0);
        final int z = Nbt.number(entity, "z", 0);
        switch (id) {
            case "minecraft:mob_spawner", "minecraft:trial_spawner" -> {
                final List<String> mobs = new ArrayList<>();
                mobs(entity, false, mobs);
                finds.spawners.add(new Finds.Spawner(x, y, z, id.equals("minecraft:trial_spawner"), List.copyOf(mobs)));
            }
            case "minecraft:beehive" ->
                finds.hives.add(new Finds.Hive(x, y, z, Nbt.list(entity, "bees").size()));
            case "minecraft:chest", "minecraft:trapped_chest" -> finds.chests.add(new Finds.Point(x, y, z));
            default -> {}
        }
    }

    /** Collects the id of every compound under a key named entity, which is where spawner data names its mob. */
    private static void mobs(final Object value, final boolean entity, final List<String> mobs) {
        final Object plain = Nbt.unwrap(value);
        if (plain instanceof Map<?, ?> map) {
            final Map<String, Object> compound = Nbt.compound(map);
            final Object id = compound.get("id");
            if (entity && id instanceof String name && !mobs.contains(name)) {
                mobs.add(name);
            }
            compound.forEach((key, inner) -> mobs(inner, key.equals("entity"), mobs));
        } else if (plain instanceof List<?> list) {
            list.forEach(inner -> mobs(inner, false, mobs));
        }
    }

    private static void blocks(final Finds finds, final int chunkX, final int chunkZ, final Map<String, Object> chunk) {
        for (final Map.Entry<Integer, Map<String, Object>> section :
                Section.of(chunk).entrySet()) {
            final Map<String, Object> states = Nbt.child(section.getValue(), "block_states");
            if (states == null) {
                continue;
            }
            final Object[] palette = Nbt.list(states, "palette").toArray();
            final int[] kinds = new int[palette.length];
            boolean any = false;
            for (int i = 0; i < palette.length; i++) {
                final String name = Section.name(palette[i]);
                if (name.endsWith("_bed") && Section.key(palette[i]).contains("part=head")) {
                    kinds[i] = 1;
                    any = true;
                } else if (name.equals("minecraft:end_portal_frame")) {
                    kinds[i] = 2;
                    any = true;
                }
            }
            if (!any) {
                continue;
            }
            final int[] indices = Section.blockIndices(states);
            for (int i = 0; i < Section.BLOCKS; i++) {
                final int kind = kinds[indices[i]];
                if (kind == 0) {
                    continue;
                }
                final Finds.Point point = new Finds.Point(
                        chunkX * 16 + (i & 15), section.getKey() * 16 + (i >> 8), chunkZ * 16 + (i >> 4 & 15));
                (kind == 1 ? finds.beds : finds.frames).add(point);
            }
        }
    }

    private static void starts(final Finds finds, final int chunkX, final int chunkZ, final Map<String, Object> chunk) {
        final Map<String, Object> structures = Nbt.child(chunk, "structures");
        final @Nullable Map<String, Object> starts = structures == null ? null : Nbt.child(structures, "starts");
        if (starts == null) {
            return;
        }
        for (final Object element : starts.values()) {
            final Map<String, Object> start = Nbt.compound(element);
            @Nullable Box box = null;
            for (final Object child : Nbt.list(start, "Children")) {
                if (Nbt.compound(child).get("BB") instanceof int[] corners && corners.length == 6) {
                    box = box == null ? Box.of(corners) : box.union(Box.of(corners));
                }
            }
            if (box != null && !Nbt.text(start, "id").equals("INVALID")) {
                finds.starts.add(new Finds.Start(Nbt.text(start, "id"), chunkX, chunkZ, box));
            }
        }
    }
}
