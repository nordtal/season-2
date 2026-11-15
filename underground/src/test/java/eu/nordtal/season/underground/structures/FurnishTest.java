package eu.nordtal.season.underground.structures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FurnishTest {

    private static final long SEED = 27_594_263L;
    private static final int CHUNKS = 8;
    private static final List<int[]> REGIONS = List.of(new int[] {0, 0});

    @Test
    void anOutpostGetsTwoAllaysInsideItsClosedHull(@TempDir final Path directory) throws IOException {
        final Path out = run(world(directory), directory.resolve("out"));

        final List<Map<String, Object>> allays = allays(out);
        assertEquals(2, allays.size());
        for (final Map<String, Object> allay : allays) {
            final NbtList pos = Nbt.list(allay, "Pos");
            final double x = (double) pos.get(0);
            final double y = (double) pos.get(1);
            final double z = (double) pos.get(2);
            assertTrue(x > 19 && x < 29 && z > 19 && z < 29, "inside the hull: " + pos);
            assertTrue(y >= 65 && y < 72, "inside the hull: " + pos);
            assertEquals((byte) 1, allay.get("PersistenceRequired"));
        }
    }

    @Test
    void anOutpostWithoutAClosedRoomGetsItsAllaysFreeOnItsHighestFloor(@TempDir final Path directory)
            throws IOException {
        final Path out = run(world(directory), directory.resolve("out"));

        assertTrue(report(out).stream().anyMatch(line -> line.startsWith("allays\t16 64 80 31 66 95\tfree\t")));
    }

    @Test
    void anOutpostWithAFenceAndNoClosedRoomGetsItsAllaysOnALeadToTheFence(@TempDir final Path directory)
            throws IOException {
        final Path out = run(world(directory), directory.resolve("out"));

        final List<Map<String, Object>> leashed = new ArrayList<>();
        for (final Map<String, Object> chunk :
                Region.read(Region.file(out.resolve("entities"), 0, 0)).values()) {
            for (final Object element : Nbt.list(chunk, "Entities")) {
                final Map<String, Object> entity = Nbt.compound(element);
                if (entity.get("leash") instanceof int[]) {
                    leashed.add(entity);
                }
            }
        }
        assertEquals(2, leashed.size());
        for (final Map<String, Object> allay : leashed) {
            assertEquals(
                    List.of(88, 65, 24),
                    java.util.Arrays.stream((int[]) allay.get("leash")).boxed().toList());
            assertEquals(List.of(88.5, 66.0, 24.5), List.copyOf(Nbt.list(allay, "Pos")));
        }
    }

    @Test
    void aDoorACandleOrASlabLetsAnAllayThroughAndAChestOrAFenceDoesNot() {
        assertTrue(Furnish.passable("spruce_door"));
        assertTrue(Furnish.passable("iron_trapdoor"));
        assertTrue(Furnish.passable("oak_fence_gate"));
        assertTrue(Furnish.passable("red_candle"));
        assertFalse(Furnish.passable("oak_fence"));
        assertFalse(Furnish.passable("chest"));
        assertTrue(Furnish.passable("oak_slab"));
        assertFalse(Furnish.passable("stone_bricks"));
    }

    @Test
    void anOutpostWithoutAnEmptyChestRetablesTheChestOfItsCommonestTableNearestItsMiddle(@TempDir final Path directory)
            throws IOException {
        final Path out = run(world(directory), directory.resolve("out"));

        assertEquals(Furnish.OUTPOST_LOOT, table(out, 24, 65, 24));
        assertEquals("minecraft:chests/jungle_temple", table(out, 20, 65, 20));
        assertNull(table(out, 22, 65, 22));
        assertEquals(
                1,
                Nbt.list(Objects.requireNonNull(blockEntity(out, 22, 65, 22)), "Items")
                        .size());
    }

    @Test
    void aTaigaVillageGivesItsEmptyChestsAndOtherHouseChestsTheTaigaHouseTable(@TempDir final Path directory)
            throws IOException {
        final Path out = run(world(directory), directory.resolve("out"));

        assertEquals(Furnish.TAIGA_LOOT, table(out, 90, 64, 90));
        assertEquals(Furnish.TAIGA_LOOT, table(out, 100, 64, 100));
        assertEquals("minecraft:chests/village/village_armorer", table(out, 95, 64, 95));
        assertTrue(Objects.requireNonNull(blockEntity(out, 90, 64, 90)).get("LootTableSeed") instanceof Long);
    }

    @Test
    void aSuspiciousBlockRollsTheTableOfItsWaterAndItsPlaceAndOneThatHoldsAnItemStays(@TempDir final Path directory)
            throws IOException {
        final Path out = run(world(directory), directory.resolve("out"));

        assertEquals(Archaeology.WARM, table(out, 40, 60, 40));
        assertEquals(Archaeology.WELL, table(out, 42, 63, 42));
        assertEquals(Archaeology.COLD, table(out, 46, 60, 46));
        assertTrue(List.of(Archaeology.TRAIL, Archaeology.TRAIL_RARE).contains(table(out, 44, 63, 44)));
        assertNull(table(out, 48, 63, 48));
        assertTrue(
                Objects.requireNonNull(blockEntity(out, 48, 63, 48)).toString().contains("minecraft:bone"));
        final Map<String, Object> warm = Objects.requireNonNull(blockEntity(out, 40, 60, 40));
        assertTrue(warm.get("LootTableSeed") instanceof Long);
        assertFalse(warm.containsKey("item"));
    }

    @Test
    void sandRollsAnOceanRuinUnderWaterAndADesertTableOnLandAndGravelAColdRuinOrTheTrailRuins() {
        assertEquals(Archaeology.WARM, Archaeology.table("minecraft:suspicious_sand", true, true, false));
        assertEquals(Archaeology.PYRAMID, Archaeology.table("minecraft:suspicious_sand", false, true, false));
        assertEquals(Archaeology.WELL, Archaeology.table("minecraft:suspicious_sand", false, false, true));
        assertEquals(Archaeology.COLD, Archaeology.table("minecraft:suspicious_gravel", true, false, true));
        assertEquals(Archaeology.TRAIL, Archaeology.table("minecraft:suspicious_gravel", false, true, false));
        assertEquals(Archaeology.TRAIL_RARE, Archaeology.table("minecraft:suspicious_gravel", false, true, true));
    }

    @Test
    void chunksOfSuspiciousBlocksThatTouchAtASideOrACornerAreOnePlace() {
        final Map<Long, Integer> sizes = Archaeology.places(List.of(
                new Finds.Point(1, 60, 1),
                new Finds.Point(2, 60, 2),
                new Finds.Point(17, 60, 17),
                new Finds.Point(-15, 60, 17),
                new Finds.Point(100, 60, 100)));

        assertEquals(4, sizes.get(Finds.key(0, 0)));
        assertEquals(4, sizes.get(Finds.key(1, 1)));
        assertEquals(4, sizes.get(Finds.key(-1, 1)));
        assertEquals(1, sizes.get(Finds.key(6, 6)));
    }

    @Test
    void aSecondRunOnTheFurnishedWorldWritesNothing(@TempDir final Path directory) throws IOException {
        final Path world = world(directory);
        final Path first = run(world, directory.resolve("first"));
        for (final String kind : List.of("region", "entities")) {
            Files.copy(
                    first.resolve(kind).resolve("r.0.0.mca"),
                    world.resolve(kind).resolve("r.0.0.mca"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        final Path second = run(world, directory.resolve("second"));

        assertFalse(Files.exists(second.resolve("region")));
        assertFalse(Files.exists(second.resolve("entities")));
        assertTrue(report(second).stream()
                .filter(line -> line.startsWith("allays\t"))
                .allMatch(line -> line.contains("\tskip\t")));
    }

    @Test
    void aHouseTableOfAnotherVillageIsOneToReplaceAndAnyOtherTableIsNot() {
        assertTrue(Furnish.otherHouse("minecraft:chests/village/village_plains_house", Furnish.TAIGA_LOOT));
        assertFalse(Furnish.otherHouse("minecraft:chests/village/village_armorer", Furnish.TAIGA_LOOT));
        assertFalse(Furnish.otherHouse(Furnish.TAIGA_LOOT, Furnish.TAIGA_LOOT));
        assertFalse(Furnish.otherHouse("minecraft:chests/village/village_plains_house", Furnish.OUTPOST_LOOT));
    }

    @Test
    void aRunChangesNoChunkOutsideTheGivenRegions(@TempDir final Path directory) throws IOException {
        assertEquals(java.util.Set.of("0.0", "19.-1"), Furnish.names(List.of(new int[] {0, 0}, new int[] {19, -1})));

        final Path out = run(world(directory), directory.resolve("out"));

        assertTrue(report(out).stream().noneMatch(line -> line.startsWith("outside\t")));
        assertTrue(report(out).stream()
                .filter(line -> line.startsWith("chest\t"))
                .allMatch(line -> line.contains(", rolled ")));
    }

    private static Path run(final Path world, final Path out) throws IOException {
        Furnish.run(world, SEED, out, REGIONS);
        return out;
    }

    /**
     * Builds a world of three outposts and a taiga village.
     * One outpost's stone brick hull closes a room, one is a floor of planks, and one a floor of planks with a fence;
     * the village has an empty chest, a plains house chest and an armorer's chest.
     * Suspicious sand and gravel lie under water, one with stone between, and dry, one of them holding a bone.
     */
    private static Path world(final Path directory) throws IOException {
        final Path world = directory.resolve("world");
        final Map<Integer, Map<String, Object>> chunks = new HashMap<>();
        for (int chunkX = 0; chunkX < CHUNKS; chunkX++) {
            for (int chunkZ = 0; chunkZ < CHUNKS; chunkZ++) {
                final int baseX = chunkX * 16;
                final int baseZ = chunkZ * 16;
                final Map<String, Object> chunk =
                        TestChunks.chunk(chunkX, chunkZ, (x, y, z) -> block(baseX + x, y, baseZ + z));
                properties(chunk);
                chunks.put(chunkX + chunkZ * Region.SIDE, chunk);
            }
        }
        start(chunks, 1, 1, Furnish.OUTPOST, new int[] {16, 64, 16, 31, 72, 31});
        start(chunks, 1, 5, Furnish.OUTPOST, new int[] {16, 64, 80, 31, 66, 95});
        start(chunks, 5, 1, Furnish.OUTPOST, new int[] {80, 64, 16, 95, 66, 31});
        start(chunks, 5, 5, Furnish.TAIGA, new int[] {80, 64, 80, 111, 70, 111});
        blockEntity(chunks, chest(20, 65, 20, "minecraft:chests/jungle_temple", false));
        blockEntity(chunks, chest(24, 65, 24, "minecraft:chests/jungle_temple", false));
        blockEntity(chunks, chest(22, 65, 22, null, true));
        blockEntity(chunks, chest(90, 64, 90, null, false));
        blockEntity(chunks, chest(100, 64, 100, "minecraft:chests/village/village_plains_house", false));
        blockEntity(chunks, chest(95, 64, 95, "minecraft:chests/village/village_armorer", false));
        for (final int at : new int[] {40, 42, 44, 46, 48}) {
            blockEntity(chunks, suspicious(at, at == 40 || at == 46 ? 60 : 63, at == 48));
        }
        Region.write(Region.file(world.resolve("region"), 0, 0), chunks);
        Files.createDirectories(world.resolve("entities"));
        return world;
    }

    /** Names the blocks: stone below 64, the hull, the floor of planks, the chests and the suspicious blocks. */
    private static String block(final int x, final int y, final int z) {
        if (x == z && ((x == 40 && y > 61) || (x == 46 && y > 60)) && y < 64) {
            return "minecraft:water";
        }
        if (x == z && (x == 40 || x == 42 || x == 48) && y == (x == 40 ? 60 : 63)) {
            return "minecraft:suspicious_sand";
        }
        if (x == z && (x == 44 || x == 46) && y == (x == 46 ? 60 : 63)) {
            return "minecraft:suspicious_gravel";
        }
        if ((x == 20 || x == 22 || x == 24) && y == 65 && x == z) {
            return "minecraft:chest";
        }
        if ((x == 90 || x == 95 || x == 100) && y == 64 && x == z) {
            return "minecraft:chest";
        }
        if (x >= 18 && x <= 29 && z >= 18 && z <= 29 && y >= 64 && y <= 72) {
            final boolean wall = x == 18 || x == 29 || z == 18 || z == 29 || y == 64 || y == 72;
            return wall ? "minecraft:stone_bricks" : "minecraft:air";
        }
        if (x == 88 && y == 65 && z == 24) {
            return "minecraft:oak_fence";
        }
        if (((x >= 16 && x <= 31 && z >= 80 && z <= 95) || (x >= 80 && x <= 95 && z >= 16 && z <= 31)) && y == 64) {
            return "minecraft:oak_planks";
        }
        return y < 64 ? "minecraft:stone" : "minecraft:air";
    }

    private static void start(
            final Map<Integer, Map<String, Object>> chunks,
            final int chunkX,
            final int chunkZ,
            final String id,
            final int[] box) {
        final Map<String, Object> piece = new LinkedHashMap<>();
        piece.put("id", "minecraft:jigsaw");
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

    /** Turns the palettes into compounds, as the game writes them. */
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

    private static Map<String, Object> chest(
            final int x, final int y, final int z, final @Nullable String table, final boolean items) {
        final Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("id", "minecraft:chest");
        entity.put("x", x);
        entity.put("y", y);
        entity.put("z", z);
        if (table != null) {
            entity.put("LootTable", table);
        }
        if (items) {
            final NbtList stacks = new NbtList(Nbt.COMPOUND);
            stacks.add(new LinkedHashMap<>(Map.of("id", "minecraft:stone", "count", 1, "Slot", (byte) 0)));
            entity.put("Items", stacks);
        }
        return entity;
    }

    private static Map<String, Object> suspicious(final int x, final int y, final boolean item) {
        final Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("id", "minecraft:brushable_block");
        entity.put("x", x);
        entity.put("y", y);
        entity.put("z", x);
        entity.put("item", new LinkedHashMap<>(Map.of("id", item ? "minecraft:bone" : "minecraft:air", "count", 1)));
        return entity;
    }

    private static void blockEntity(final Map<Integer, Map<String, Object>> chunks, final Map<String, Object> entity) {
        final int index = (Nbt.number(entity, "x", 0) >> 4) + (Nbt.number(entity, "z", 0) >> 4) * Region.SIDE;
        Nbt.list(chunks.get(index), "block_entities").add(entity);
    }

    private static @Nullable Map<String, Object> blockEntity(final Path out, final int x, final int y, final int z)
            throws IOException {
        final Map<String, Object> chunk =
                Region.read(Region.file(out.resolve("region"), 0, 0)).get((x >> 4) + (z >> 4) * Region.SIDE);
        for (final Object element : Nbt.list(chunk, "block_entities")) {
            final Map<String, Object> entity = Nbt.compound(element);
            if (Nbt.number(entity, "x", 0) == x && Nbt.number(entity, "y", 0) == y && Nbt.number(entity, "z", 0) == z) {
                return entity;
            }
        }
        return null;
    }

    private static @Nullable String table(final Path out, final int x, final int y, final int z) throws IOException {
        return Objects.requireNonNull(blockEntity(out, x, y, z)).get("LootTable") instanceof String text ? text : null;
    }

    private static List<Map<String, Object>> allays(final Path out) throws IOException {
        final List<Map<String, Object>> allays = new ArrayList<>();
        for (final Map<String, Object> chunk :
                Region.read(Region.file(out.resolve("entities"), 0, 0)).values()) {
            for (final Object element : Nbt.list(chunk, "Entities")) {
                final Map<String, Object> entity = Nbt.compound(element);
                final NbtList pos = Nbt.list(entity, "Pos");
                if (Nbt.text(entity, "id").equals("minecraft:allay")
                        && (double) pos.get(2) < 64
                        && (double) pos.get(0) < 64) {
                    allays.add(entity);
                }
            }
        }
        return allays;
    }

    private static List<String> report(final Path out) throws IOException {
        return Files.readAllLines(out.resolve(Furnish.REPORT), StandardCharsets.UTF_8);
    }
}
