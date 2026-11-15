package eu.nordtal.season.underground.structures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.underground.anvil.Nbt;
import eu.nordtal.season.underground.anvil.NbtList;
import eu.nordtal.season.underground.anvil.Region;
import eu.nordtal.season.underground.anvil.Section;
import eu.nordtal.season.underground.anvil.TestChunks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructuresTest {

    private static final long SEED = 27_594_263L;
    private static final int CHUNKS = 16;

    @Test
    void anEmptySpawnerBesideAHiveWithoutBeesGoesAndTheHiveGetsBees(@TempDir final Path directory)
            throws IOException, InterruptedException {
        final Path out = run(directory, Map.of());

        assertEquals("minecraft:air", block(out, 20, 70, 20));
        assertNull(blockEntity(out, 20, 70, 20));
        final NbtList bees = Nbt.list(Objects.requireNonNull(blockEntity(out, 21, 71, 20)), "bees");
        assertTrue(bees.size() == 2 || bees.size() == 3);
        final Map<String, Object> bee = Nbt.compound(bees.getFirst());
        assertEquals("minecraft:bee", Nbt.text(Nbt.compound(bee.get("entity_data")), "id"));
        assertEquals(600, Nbt.number(bee, "min_ticks_in_hive", 0));
        assertTrue(
                report(out).contains("empty spawner\t20 70 20 20 70 20\tremove\tbees added to the hive at 21 71 20"));
    }

    @Test
    void anEmptySpawnerWithNoHiveNearStaysAndIsReported(@TempDir final Path directory)
            throws IOException, InterruptedException {
        final Path out = run(directory, Map.of());

        assertTrue(
                report(out).contains("empty spawner\t40 70 200 40 70 200\tkeep\tno bee nest or hive within 4 blocks"));
    }

    @Test
    void aVillagerSpawnerGoes(@TempDir final Path directory) throws IOException, InterruptedException {
        final Path out = run(directory, Map.of());

        assertEquals("minecraft:air", block(out, 200, 30, 40));
    }

    @Test
    void thePortalRoomBecomesAStrongholdAndItsEmptySpawnerSpawnsSkeletons(@TempDir final Path directory)
            throws IOException, InterruptedException {
        final Path out = run(directory, Map.of());

        final Map<String, Object> spawner = Objects.requireNonNull(blockEntity(out, 70, 60, 70));
        assertEquals(
                "minecraft:skeleton",
                Nbt.text(Nbt.compound(Nbt.compound(spawner.get("SpawnData")).get("entity")), "id"));
        final Map<String, Map<String, Object>> starts = starts(out);
        final Map<String, Object> start = starts.get("minecraft:stronghold");
        assertEquals("minecraft:stronghold", Nbt.text(start, "id"));
        final Map<String, Object> structures =
                Nbt.compound(chunk(out, "region", 4, 4).get("structures"));
        final long[] references =
                (long[]) Nbt.compound(structures.get("References")).get("minecraft:stronghold");
        assertEquals(1, references.length);
        final long packed = references[0];
        assertEquals(start.get("ChunkX"), (int) packed);
        assertEquals(start.get("ChunkZ"), (int) (packed >> 32));
    }

    @Test
    void aBedWithAChestGetsAVillagerUnderAVillageStart(@TempDir final Path directory)
            throws IOException, InterruptedException {
        final Path out = run(directory, Map.of());

        final NbtList entities = Nbt.list(chunk(out, "entities", 9, 9), "Entities");
        assertEquals(1, entities.size());
        final Map<String, Object> villager = Nbt.compound(entities.getFirst());
        assertEquals("minecraft:villager", Nbt.text(villager, "id"));
        assertEquals("minecraft:plains", Nbt.text(Nbt.compound(villager.get("VillagerData")), "type"));
        assertTrue(starts(out).containsKey("minecraft:village_plains"));
    }

    @Test
    void aChunkToChangeOfAnOlderVersionStopsTheRunBeforeAnyFileIsWritten(@TempDir final Path directory)
            throws IOException {
        assertThrows(IllegalStateException.class, () -> run(directory, Map.of(12 + 2 * Region.SIDE, 4903)));

        assertFalse(Files.exists(directory.resolve("out/region")));
    }

    @Test
    void theNewestVersionOfTheWorldIsTheOneItWrites() {
        final Finds finds = new Finds();
        finds.versions.put(2860, 3);
        finds.versions.put(5023, 1);

        assertEquals(5023, Structures.version(finds));
    }

    /** Builds the world, runs the command and returns the folder it wrote; versions overrides chunks by index. */
    private static Path run(final Path directory, final Map<Integer, Integer> versions)
            throws IOException, InterruptedException {
        final Path world = directory.resolve("world");
        final Map<Integer, Map<String, Object>> chunks = new HashMap<>();
        for (int chunkX = 0; chunkX < CHUNKS; chunkX++) {
            for (int chunkZ = 0; chunkZ < CHUNKS; chunkZ++) {
                final int baseX = chunkX * 16;
                final int baseZ = chunkZ * 16;
                final Map<String, Object> chunk =
                        TestChunks.chunk(chunkX, chunkZ, (x, y, z) -> block(baseX + x, y, baseZ + z));
                properties(chunk);
                final int index = chunkX + chunkZ * Region.SIDE;
                chunk.put("DataVersion", versions.getOrDefault(index, 5023));
                chunks.put(index, chunk);
            }
        }
        blockEntity(chunks, spawner(20, 70, 20, null));
        blockEntity(chunks, hive(21, 71, 20));
        blockEntity(chunks, spawner(40, 70, 200, null));
        blockEntity(chunks, spawner(200, 30, 40, "minecraft:villager"));
        blockEntity(chunks, spawner(70, 60, 70, null));
        blockEntity(chunks, chest(152, 64, 150));
        Region.write(Region.file(world.resolve("region"), 0, 0), chunks);
        final Path pack = directory.resolve("pack");
        final Path sets = pack.resolve("data/nordtal/worldgen/structure_set");
        Files.createDirectories(sets);
        int salt = 945_001;
        for (final String set : List.of(Planner.VILLAGES, Planner.OUTPOSTS, Planner.MONUMENTS, Planner.STRONGHOLDS)) {
            Files.writeString(
                    sets.resolve(set + ".json"),
                    "{\"placement\": {\"type\": \"minecraft:random_spread\", \"spacing\": 8, \"separation\": 0,"
                            + " \"salt\": " + salt++ + "}}",
                    StandardCharsets.UTF_8);
        }
        final Path out = directory.resolve("out");
        Structures.run(world, pack, SEED, out, List.of(new int[] {0, 0}));
        return out;
    }

    /**
     * Names the blocks of the world: stone below 64, with the places the tests look at.
     * They are a cube of stone bricks with 12 portal frames and an empty spawner above it, a spawner beside a
     * nest, a lonely empty spawner, a villager spawner, and a bed with a chest.
     */
    private static String block(final int x, final int y, final int z) {
        if ((x == 20 && y == 70 && z == 20)
                || (x == 40 && y == 70 && z == 200)
                || (x == 200 && y == 30 && z == 40)
                || (x == 70 && y == 60 && z == 70)) {
            return "minecraft:spawner";
        }
        if (x == 21 && y == 71 && z == 20) {
            return "minecraft:bee_nest";
        }
        if (x == 150 && y == 64 && z == 150) {
            return "minecraft:red_bed[part=head]";
        }
        if (x == 152 && y == 64 && z == 150) {
            return "minecraft:chest";
        }
        if (y == 40 && z == 70 && x >= 64 && x < 76) {
            return "minecraft:end_portal_frame";
        }
        if (x >= 64 && x < 80 && z >= 64 && z < 80 && y >= 32 && y < 48) {
            return "minecraft:stone_bricks";
        }
        return y < 64 ? "minecraft:stone" : "minecraft:air";
    }

    /** Turns the palettes into compounds, as the game writes them, with the properties a name carries. */
    private static void properties(final Map<String, Object> chunk) {
        for (final Map<String, Object> section : Section.of(chunk).values()) {
            final Map<String, Object> states = Nbt.compound(section.get("block_states"));
            final NbtList palette = new NbtList(Nbt.COMPOUND);
            for (final Object entry : Nbt.list(states, "palette")) {
                final String text = (String) entry;
                final Map<String, Object> state = new LinkedHashMap<>();
                final int open = text.indexOf('[');
                state.put("Name", open < 0 ? text : text.substring(0, open));
                if (open >= 0) {
                    final int equals = text.indexOf('=', open);
                    state.put(
                            "Properties",
                            new LinkedHashMap<>(Map.of(
                                    text.substring(open + 1, equals), text.substring(equals + 1, text.length() - 1))));
                }
                palette.add(state);
            }
            states.put("palette", palette);
        }
    }

    private static Map<String, Object> spawner(final int x, final int y, final int z, final @Nullable String mob) {
        final Map<String, Object> entity = position("minecraft:mob_spawner", x, y, z);
        if (mob != null) {
            entity.put("SpawnData", new LinkedHashMap<>(Map.of("entity", new LinkedHashMap<>(Map.of("id", mob)))));
        }
        return entity;
    }

    private static Map<String, Object> hive(final int x, final int y, final int z) {
        final Map<String, Object> entity = position("minecraft:beehive", x, y, z);
        entity.put("bees", new NbtList(Nbt.COMPOUND));
        return entity;
    }

    private static Map<String, Object> chest(final int x, final int y, final int z) {
        return position("minecraft:chest", x, y, z);
    }

    private static Map<String, Object> position(final String id, final int x, final int y, final int z) {
        final Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("id", id);
        entity.put("x", x);
        entity.put("y", y);
        entity.put("z", z);
        return entity;
    }

    private static void blockEntity(final Map<Integer, Map<String, Object>> chunks, final Map<String, Object> entity) {
        final int index = (Nbt.number(entity, "x", 0) >> 4) + (Nbt.number(entity, "z", 0) >> 4) * Region.SIDE;
        Nbt.list(chunks.get(index), "block_entities").add(entity);
    }

    private static Map<String, Object> chunk(final Path out, final String kind, final int chunkX, final int chunkZ)
            throws IOException {
        return Region.read(Region.file(out.resolve(kind), 0, 0)).get(chunkX + chunkZ * Region.SIDE);
    }

    private static String block(final Path out, final int x, final int y, final int z) throws IOException {
        final Area area = new Area(out);
        area.load(new Box(x, y, z, x, y, z), 0);
        return "minecraft:" + area.name(x, y, z);
    }

    private static @Nullable Map<String, Object> blockEntity(final Path out, final int x, final int y, final int z)
            throws IOException {
        for (final Object element : Nbt.list(chunk(out, "region", x >> 4, z >> 4), "block_entities")) {
            final Map<String, Object> entity = Nbt.compound(element);
            if (Nbt.number(entity, "x", 0) == x && Nbt.number(entity, "y", 0) == y && Nbt.number(entity, "z", 0) == z) {
                return entity;
            }
        }
        return null;
    }

    /** Returns every start the run wrote, by its key. */
    private static Map<String, Map<String, Object>> starts(final Path out) throws IOException {
        final Map<String, Map<String, Object>> starts = new HashMap<>();
        for (final Map<String, Object> chunk :
                Region.read(Region.file(out.resolve("region"), 0, 0)).values()) {
            final Map<String, Object> structures = Nbt.child(chunk, "structures");
            final @Nullable Map<String, Object> here = structures == null ? null : Nbt.child(structures, "starts");
            if (here != null) {
                here.forEach((key, value) -> starts.put(key, Nbt.compound(value)));
            }
        }
        return starts;
    }

    private static List<String> report(final Path out) throws IOException {
        return Files.readAllLines(out.resolve(Structures.REPORT), StandardCharsets.UTF_8);
    }
}
