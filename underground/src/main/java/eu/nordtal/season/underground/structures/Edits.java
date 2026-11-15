package eu.nordtal.season.underground.structures;

import eu.nordtal.season.underground.anvil.Nbt;
import eu.nordtal.season.underground.anvil.NbtList;
import eu.nordtal.season.underground.anvil.Region;
import eu.nordtal.season.underground.anvil.Section;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** Writes a plan into the region and entity files it touches, each read once and written once. */
public final class Edits {

    /** What the writing did, by kind of change. */
    public record Summary(
            int regionFiles,
            int entityFiles,
            int starts,
            int references,
            int removed,
            int retyped,
            int bees,
            int villagers,
            int missing) {}

    @FunctionalInterface
    private interface Change {
        /** Changes a chunk, returning whether it found what it changes. */
        boolean apply(Map<String, Object> chunk);
    }

    private static final String REGION = "region";
    private static final String ENTITIES = "entities";

    private final Path world;
    private final Path out;
    private final Finds finds;
    private final int dataVersion;
    private final Random random;
    private final Map<String, Map<Integer, List<Change>>> files = new TreeMap<>();
    private final List<String> older = new ArrayList<>();
    private int references;
    private int missing;

    public Edits(final Path world, final Path out, final Finds finds, final int dataVersion, final long seed) {
        this.world = world;
        this.out = out;
        this.finds = finds;
        this.dataVersion = dataVersion;
        this.random = new Random(seed);
    }

    /** Writes every change of a plan and returns what it did. */
    public Summary write(final Plan plan) throws IOException {
        for (final Plan.Start start : plan.starts()) {
            start(start);
        }
        for (final Plan.Removal removal : plan.removals()) {
            final Finds.Spawner spawner = removal.spawner();
            add(
                    REGION,
                    spawner.x() >> 4,
                    spawner.z() >> 4,
                    chunk -> remove(chunk, spawner.x(), spawner.y(), spawner.z()));
        }
        for (final Plan.Retype retype : plan.retypes()) {
            final Finds.Spawner spawner = retype.spawner();
            add(REGION, spawner.x() >> 4, spawner.z() >> 4, chunk -> retype(chunk, spawner, retype.mob()));
        }
        for (final Plan.Bees bees : plan.bees()) {
            final Finds.Hive hive = bees.hive();
            final List<Map<String, Object>> inside = new ArrayList<>();
            for (int i = 0; i < bees.count(); i++) {
                inside.add(bee());
            }
            add(REGION, hive.x() >> 4, hive.z() >> 4, chunk -> bees(chunk, hive, inside));
        }
        for (final Plan.Villager villager : plan.villagers()) {
            final Map<String, Object> entity = villager(villager);
            final int chunkX = (int) Math.floor(villager.x()) >> 4;
            final int chunkZ = (int) Math.floor(villager.z()) >> 4;
            add(ENTITIES, chunkX, chunkZ, chunk -> {
                entities(chunk, chunkX, chunkZ).add(entity);
                return true;
            });
        }
        refuseOlder();
        int regionFiles = 0;
        int entityFiles = 0;
        for (final Map.Entry<String, Map<Integer, List<Change>>> file : files.entrySet()) {
            apply(file.getKey(), file.getValue());
            if (file.getKey().startsWith(REGION)) {
                regionFiles++;
            } else {
                entityFiles++;
            }
        }
        return new Summary(
                regionFiles,
                entityFiles,
                plan.starts().size(),
                references,
                plan.removals().size(),
                plan.retypes().size(),
                plan.bees().size(),
                plan.villagers().size(),
                missing);
    }

    /** Refuses the plan before anything is written when a chunk to change is not at the newest data version. */
    private void refuseOlder() throws IOException {
        for (final Map.Entry<String, Map<Integer, List<Change>>> file : files.entrySet()) {
            final Path source = world.resolve(file.getKey());
            if (file.getKey().startsWith(ENTITIES) && Files.exists(source)) {
                final Map<Integer, Map<String, Object>> chunks = Region.read(source);
                for (final int index : file.getValue().keySet()) {
                    final @Nullable Map<String, Object> chunk = chunks.get(index);
                    final int version = chunk == null ? dataVersion : Nbt.number(chunk, "DataVersion", -1);
                    if (version != dataVersion) {
                        older.add("entities of chunk " + index + " in " + file.getKey() + " at " + version);
                    }
                }
            }
        }
        if (!older.isEmpty()) {
            throw new IllegalStateException(older.size() + " chunks to change are not at data version " + dataVersion
                    + ", which the game would upgrade without knowing the new data; upgrade the world first. The first: "
                    + older.getFirst());
        }
    }

    private void add(final String kind, final int chunkX, final int chunkZ, final Change change) {
        final int version = finds.version(chunkX, chunkZ);
        if (version != -1 && version != dataVersion) {
            older.add("chunk " + chunkX + " " + chunkZ + " at " + version);
        }
        files.computeIfAbsent(kind + "/r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca", key -> new TreeMap<>())
                .computeIfAbsent((chunkX & 31) + (chunkZ & 31) * Region.SIDE, key -> new ArrayList<>())
                .add(change);
    }

    private void apply(final String name, final Map<Integer, List<Change>> changes) throws IOException {
        final Path source = world.resolve(name);
        final Map<Integer, Map<String, Object>> chunks =
                Files.exists(source) ? new HashMap<>(Region.read(source)) : new HashMap<>();
        for (final Map.Entry<Integer, List<Change>> entry : changes.entrySet()) {
            @Nullable Map<String, Object> chunk = chunks.get(entry.getKey());
            if (chunk == null && name.startsWith(ENTITIES)) {
                chunk = new LinkedHashMap<>();
                chunks.put(entry.getKey(), chunk);
            }
            for (final Change change : entry.getValue()) {
                if (chunk == null || !change.apply(chunk)) {
                    missing++;
                }
            }
        }
        Region.write(out.resolve(name), chunks);
    }

    private void start(final Plan.Start start) {
        final Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("id", start.structure());
        tag.put("ChunkX", start.chunkX());
        tag.put("ChunkZ", start.chunkZ());
        tag.put("references", 0);
        final NbtList children = new NbtList(Nbt.COMPOUND);
        final Set<Long> touched = new LinkedHashSet<>();
        for (final Box piece : start.pieces()) {
            final Map<String, Object> child = new LinkedHashMap<>();
            child.put("id", start.piece());
            child.put("BB", piece.corners());
            child.put("O", -1);
            child.put("GD", 0);
            children.add(child);
            for (int chunkX = piece.minChunkX(); chunkX <= piece.maxChunkX(); chunkX++) {
                for (int chunkZ = piece.minChunkZ(); chunkZ <= piece.maxChunkZ(); chunkZ++) {
                    touched.add(Finds.key(chunkX, chunkZ));
                }
            }
        }
        tag.put("Children", children);
        add(REGION, start.chunkX(), start.chunkZ(), chunk -> {
            structures(chunk, "starts").put(start.structure(), tag);
            return true;
        });
        final long packed = (start.chunkX() & 0xFFFF_FFFFL) | ((long) start.chunkZ() << 32);
        for (final long key : touched) {
            references++;
            add(REGION, (int) (key >> 32), (int) key, chunk -> {
                final Map<String, Object> all = structures(chunk, "References");
                final long[] old = all.get(start.structure()) instanceof long[] longs ? longs : new long[0];
                if (Arrays.stream(old).noneMatch(value -> value == packed)) {
                    final long[] now = Arrays.copyOf(old, old.length + 1);
                    now[old.length] = packed;
                    all.put(start.structure(), now);
                }
                return true;
            });
        }
    }

    private static Map<String, Object> structures(final Map<String, Object> chunk, final String key) {
        Map<String, Object> structures = Nbt.child(chunk, "structures");
        if (structures == null) {
            structures = new LinkedHashMap<>();
            chunk.put("structures", structures);
        }
        Map<String, Object> inner = Nbt.child(structures, key);
        if (inner == null) {
            inner = new LinkedHashMap<>();
            structures.put(key, inner);
        }
        return inner;
    }

    /** Takes a spawner out, leaving water where water touches it in its chunk and air elsewhere. */
    static boolean remove(final Map<String, Object> chunk, final int x, final int y, final int z) {
        final Map<String, Object> section = Section.of(chunk).get(y >> 4);
        final Map<String, Object> states = section == null ? null : Nbt.child(section, "block_states");
        if (states == null) {
            return false;
        }
        final NbtList palette = Nbt.list(states, "palette");
        final int[] indices = Section.blockIndices(states);
        final int cell = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        if (!Section.name(palette.get(indices[cell])).endsWith("spawner")) {
            return false;
        }
        int at = water(palette, indices, x & 15, y & 15, z & 15);
        for (int i = 0; i < palette.size() && at < 0; i++) {
            if (Section.key(palette.get(i)).equals("minecraft:air")) {
                at = i;
            }
        }
        if (at < 0) {
            at = palette.size();
            palette.add(
                    palette.getFirst() instanceof Map
                            ? new LinkedHashMap<>(Map.of("", "minecraft:air"))
                            : "minecraft:air");
        }
        indices[cell] = at;
        states.put("data", Section.pack(indices, palette.size(), 4));
        Nbt.list(chunk, "block_entities").removeIf(element -> at(Nbt.compound(element), x, y, z));
        chunk.remove("Heightmaps");
        chunk.put("isLightOn", (byte) 0);
        return true;
    }

    /** Returns the palette index of still water beside a cell in its section, or -1 where none touches it. */
    private static int water(final NbtList palette, final int[] indices, final int x, final int y, final int z) {
        for (final int[] step : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
            final int nx = x + step[0];
            final int ny = y + step[1];
            final int nz = z + step[2];
            if (nx < 0 || nx > 15 || ny < 0 || ny > 15 || nz < 0 || nz > 15) {
                continue;
            }
            final int index = indices[(ny << 8) | (nz << 4) | nx];
            if (Section.key(palette.get(index)).equals("minecraft:water{level=0}")) {
                return index;
            }
        }
        return -1;
    }

    private static boolean at(final Map<String, Object> entity, final int x, final int y, final int z) {
        return Nbt.number(entity, "x", 0) == x && Nbt.number(entity, "y", 0) == y && Nbt.number(entity, "z", 0) == z;
    }

    private static @Nullable Map<String, Object> blockEntity(
            final Map<String, Object> chunk, final int x, final int y, final int z) {
        for (final Object element : Nbt.list(chunk, "block_entities")) {
            if (at(Nbt.compound(element), x, y, z)) {
                return Nbt.compound(element);
            }
        }
        return null;
    }

    /** Gives an empty spawner a mob to spawn. */
    static boolean retype(final Map<String, Object> chunk, final Finds.Spawner spawner, final String mob) {
        final Map<String, Object> entity = blockEntity(chunk, spawner.x(), spawner.y(), spawner.z());
        if (entity == null || !Nbt.text(entity, "id").equals("minecraft:mob_spawner")) {
            return false;
        }
        final Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("id", mob);
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("entity", inner);
        entity.put("SpawnData", data);
        entity.remove("SpawnPotentials");
        return true;
    }

    /** Puts bees into a hive. */
    static boolean bees(
            final Map<String, Object> chunk, final Finds.Hive hive, final List<Map<String, Object>> inside) {
        final Map<String, Object> entity = blockEntity(chunk, hive.x(), hive.y(), hive.z());
        if (entity == null || !Nbt.text(entity, "id").equals("minecraft:beehive")) {
            return false;
        }
        NbtList bees = Nbt.list(entity, "bees");
        if (!(entity.get("bees") instanceof NbtList)) {
            bees = new NbtList(Nbt.COMPOUND);
            entity.put("bees", bees);
        }
        if (bees.isEmpty()) {
            bees.addAll(inside);
        }
        return true;
    }

    /** Returns a bee in a hive as a natural nest holds it, ready to leave after its time inside. */
    private Map<String, Object> bee() {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", "minecraft:bee");
        final Map<String, Object> bee = new LinkedHashMap<>();
        bee.put("entity_data", data);
        bee.put("ticks_in_hive", random.nextInt(599));
        bee.put("min_ticks_in_hive", 600);
        return bee;
    }

    private Map<String, Object> villager(final Plan.Villager villager) {
        final Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("id", "minecraft:villager");
        final NbtList position = new NbtList(Nbt.DOUBLE);
        position.add(villager.x());
        position.add(villager.y());
        position.add(villager.z());
        entity.put("Pos", position);
        final NbtList rotation = new NbtList(Nbt.FLOAT);
        rotation.add(random.nextFloat() * 360f);
        rotation.add(0f);
        entity.put("Rotation", rotation);
        entity.put("UUID", new int[] {random.nextInt(), random.nextInt(), random.nextInt(), random.nextInt()});
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", villager.type());
        data.put("profession", "minecraft:none");
        data.put("level", 1);
        entity.put("VillagerData", data);
        return entity;
    }

    private NbtList entities(final Map<String, Object> chunk, final int chunkX, final int chunkZ) {
        if (chunk.isEmpty()) {
            chunk.put("DataVersion", dataVersion);
            chunk.put("Position", new int[] {chunkX, chunkZ});
            chunk.put("Entities", new NbtList(Nbt.COMPOUND));
        }
        if (!(chunk.get("Entities") instanceof NbtList)) {
            chunk.put("Entities", new NbtList(Nbt.COMPOUND));
        }
        return Nbt.list(chunk, "Entities");
    }
}
