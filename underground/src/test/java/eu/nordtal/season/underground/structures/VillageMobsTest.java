package eu.nordtal.season.underground.structures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.underground.anvil.Nbt;
import eu.nordtal.season.underground.anvil.NbtList;
import eu.nordtal.season.underground.anvil.Region;
import eu.nordtal.season.underground.anvil.Section;
import eu.nordtal.season.underground.anvil.TestChunks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VillageMobsTest {

    private static final long SEED = 27_594_263L;
    private static final int CHUNKS = 8;
    private static final List<int[]> REGIONS = List.of(new int[] {0, 0});

    @Test
    void aVillageWithTwoToNineVillagersGetsOneIronGolemAndTwoToThreeCats(@TempDir final Path directory)
            throws IOException {
        final Path world = world(directory, 5, false);
        final Path out = directory.resolve("out");

        Furnish.run(world, SEED, out, REGIONS);

        final List<Map<String, Object>> golems = entities(out, "minecraft:iron_golem");
        final List<Map<String, Object>> cats = entities(out, "minecraft:cat");
        assertEquals(1, golems.size());
        assertTrue(cats.size() == 2 || cats.size() == 3, "cats=" + cats.size());
        assertEquals((byte) 0, golems.getFirst().get("PlayerCreated"));
        assertEquals((byte) 1, golems.getFirst().get("PersistenceRequired"));
        for (final Map<String, Object> cat : cats) {
            assertEquals((byte) 1, cat.get("PersistenceRequired"));
            assertTrue(Nbt.text(cat, "variant").startsWith("minecraft:"), "variant=" + cat.get("variant"));
            assertTrue(
                    Nbt.text(cat, "sound_variant").startsWith("minecraft:"),
                    "sound_variant=" + cat.get("sound_variant"));
        }
        assertFalse(Files.exists(out.resolve("region")));
    }

    @Test
    void aVillageWithTenOrMoreVillagersGetsTwoIronGolemsAndTwoToThreeCats(@TempDir final Path directory)
            throws IOException {
        final Path world = world(directory, 10, false);
        final Path out = directory.resolve("out");

        Furnish.run(world, SEED, out, REGIONS);

        assertEquals(2, entities(out, "minecraft:iron_golem").size());
        final int cats = entities(out, "minecraft:cat").size();
        assertTrue(cats == 2 || cats == 3, "cats=" + cats);
    }

    @Test
    void aVillageWithOneVillagerGetsCatsAndNoIronGolem(@TempDir final Path directory) throws IOException {
        final Path world = world(directory, 1, false);
        final Path out = directory.resolve("out");

        Furnish.run(world, SEED, out, REGIONS);

        assertEquals(0, entities(out, "minecraft:iron_golem").size());
        final int cats = entities(out, "minecraft:cat").size();
        assertTrue(cats == 2 || cats == 3, "cats=" + cats);
    }

    @Test
    void anEmptyVillageGetsNoIronGolemAndNoCats(@TempDir final Path directory) throws IOException {
        final Path world = world(directory, 0, false);
        final Path out = directory.resolve("out");

        Furnish.run(world, SEED, out, REGIONS);

        assertFalse(Files.exists(out.resolve("entities")));
    }

    @Test
    void aSecondRunWritesNoAdditionalGolemsOrCats(@TempDir final Path directory) throws IOException {
        final Path world = world(directory, 10, false);
        final Path first = directory.resolve("first");
        Furnish.run(world, SEED, first, REGIONS);
        Files.copy(
                first.resolve("entities/r.0.0.mca"),
                world.resolve("entities/r.0.0.mca"),
                StandardCopyOption.REPLACE_EXISTING);

        final Path second = directory.resolve("second");
        Furnish.run(world, SEED, second, REGIONS);

        assertFalse(Files.exists(second.resolve("entities")));
    }

    @Test
    void ironGolemsAvoidTightInteriorsAndSweetBerryBushes(@TempDir final Path directory) throws IOException {
        final Path world = world(directory, 4, true);
        final Path out = directory.resolve("out");

        Furnish.run(world, SEED, out, REGIONS);

        final List<Map<String, Object>> golems = entities(out, "minecraft:iron_golem");
        assertEquals(1, golems.size());
        final NbtList pos = Nbt.list(golems.getFirst(), "Pos");
        final int gx = (int) Math.floor((double) pos.get(0));
        final int gy = (int) Math.floor((double) pos.get(1));
        final int gz = (int) Math.floor((double) pos.get(2));
        assertEquals(64, gy);
        assertFalse(gx >= 20 && gx <= 24 && gz >= 20 && gz <= 24, "golem inside tight house: " + pos);
        assertFalse(gx >= 25 && gx <= 27 && gz >= 20 && gz <= 24, "golem on sweet berry bushes: " + pos);
        assertFalse(Files.exists(out.resolve("region")));
    }

    private static Path world(final Path directory, final int villagerCount, final boolean withHouseAndBerries)
            throws IOException {
        final Path world = directory.resolve("world");
        final Map<Integer, Map<String, Object>> chunks = new HashMap<>();
        for (int chunkX = 0; chunkX < CHUNKS; chunkX++) {
            for (int chunkZ = 0; chunkZ < CHUNKS; chunkZ++) {
                final int baseX = chunkX * 16;
                final int baseZ = chunkZ * 16;
                final Map<String, Object> chunk = TestChunks.chunk(
                        chunkX, chunkZ, (x, y, z) -> block(baseX + x, y, baseZ + z, withHouseAndBerries));
                properties(chunk);
                chunks.put(chunkX + chunkZ * Region.SIDE, chunk);
            }
        }
        start(chunks, 1, 1, "minecraft:village_plains", new int[] {16, 64, 16, 47, 72, 47});
        Region.write(Region.file(world.resolve("region"), 0, 0), chunks);
        final Map<Integer, Map<String, Object>> entityChunks = new HashMap<>();
        if (villagerCount > 0) {
            final NbtList list = new NbtList(Nbt.COMPOUND);
            for (int i = 0; i < villagerCount; i++) {
                list.add(villager(22.5, 64.0, 22.5));
            }
            final Map<String, Object> entityChunk = new LinkedHashMap<>();
            entityChunk.put("DataVersion", 5023);
            entityChunk.put("Position", new int[] {1, 1});
            entityChunk.put("Entities", list);
            entityChunks.put(1 + Region.SIDE, entityChunk);
        }
        Region.write(Region.file(world.resolve("entities"), 0, 0), entityChunks);
        return world;
    }

    private static String block(final int x, final int y, final int z, final boolean withHouseAndBerries) {
        if (withHouseAndBerries) {
            if (x >= 20 && x <= 24 && z >= 20 && z <= 24 && y >= 64 && y <= 67) {
                final boolean inside = x == 22 && z == 22 && (y == 64 || y == 65);
                return inside ? "minecraft:air" : "minecraft:oak_planks";
            }
            if (x >= 25 && x <= 27 && z >= 20 && z <= 24 && y == 64) {
                return "minecraft:sweet_berry_bush";
            }
        }
        return y < 64 ? "minecraft:grass_block" : "minecraft:air";
    }

    private static Map<String, Object> villager(final double x, final double y, final double z) {
        final Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("id", "minecraft:villager");
        final NbtList pos = new NbtList(Nbt.DOUBLE);
        pos.add(x);
        pos.add(y);
        pos.add(z);
        entity.put("Pos", pos);
        return entity;
    }

    private static void start(
            final Map<Integer, Map<String, Object>> chunks,
            final int chunkX,
            final int chunkZ,
            final String id,
            final int[] box) {
        final Map<String, Object> piece = new LinkedHashMap<>();
        piece.put("id", "minecraft:btp");
        piece.put("BB", box);
        final NbtList children = new NbtList(Nbt.COMPOUND);
        children.add(piece);
        final Map<String, Object> start = new LinkedHashMap<>();
        start.put("id", id);
        start.put("ChunkX", chunkX);
        start.put("ChunkZ", chunkZ);
        start.put("Children", children);
        final Map<String, Object> starts = new LinkedHashMap<>();
        starts.put(id, start);
        final Map<String, Object> structures = new LinkedHashMap<>();
        structures.put("starts", starts);
        structures.put("References", new LinkedHashMap<>());
        chunks.get(chunkX + chunkZ * Region.SIDE).put("structures", structures);
    }

    private static void properties(final Map<String, Object> chunk) {
        for (final Map<String, Object> section : Section.of(chunk).values()) {
            final Map<String, Object> states = Nbt.compound(section.get("block_states"));
            final NbtList palette = new NbtList(Nbt.COMPOUND);
            for (final Object entry : Nbt.list(states, "palette")) {
                palette.add(new LinkedHashMap<>(Map.of("Name", (String) entry)));
            }
            states.put("palette", palette);
        }
    }

    private static List<Map<String, Object>> entities(final Path out, final String id) throws IOException {
        final List<Map<String, Object>> result = new ArrayList<>();
        final Path file = Region.file(out.resolve("entities"), 0, 0);
        if (!Files.exists(file)) {
            return result;
        }
        for (final Map<String, Object> chunk : Region.read(file).values()) {
            for (final Object element : Nbt.list(chunk, "Entities")) {
                final Map<String, Object> entity = Nbt.compound(element);
                if (Nbt.text(entity, "id").equals(id)) {
                    result.add(entity);
                }
            }
        }
        return result;
    }
}
