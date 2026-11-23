package eu.nordtal.season.smp.duel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.inbox.AreaShape.Corner;
import eu.nordtal.season.smp.region.PolygonArea;
import eu.nordtal.season.smp.region.PolygonAreas;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.block.BlockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArenaCopyTest {

    @TempDir
    Path tempDir;

    /** Holds block data plus optional tile-entity NBT (e.g. sign text, chest contents) for testing. */
    private record StoredBlock(String blockData, String blockEntityNbt) {}

    @Test
    void snapshotAndRestorePreserveBlockEntityStateAndOnlyTouchBlocksInsideThe3dPolygon() {
        final PolygonArea lArena = new PolygonArea(
                "Arena",
                "nordtal",
                List.of(
                        new Corner(0, 0),
                        new Corner(4, 0),
                        new Corner(4, 4),
                        new Corner(2, 4),
                        new Corner(2, 2),
                        new Corner(0, 2)),
                64,
                65);
        final Map<String, StoredBlock> world = stoneCube();
        world.put(key("nordtal", 1, 64, 1), new StoredBlock("minecraft:oak_sign", "{front_text:\"Fight!\"}"));
        world.put(key("nordtal", 0, 64, 4), new StoredBlock("minecraft:chest", "{Items:[{id:\"minecraft:diamond\"}]}"));

        final Map<String, List<BlockState>> structures = new HashMap<>();
        final Map<BlockState, StoredBlock> statePayloads = new HashMap<>();
        final File file = tempDir.resolve(ArenaCopy.FILE).toFile();
        final ArenaCopy copy = new ArenaCopy(Logger.getLogger("test"), file);

        assertTrue(copy.save(
                new PolygonAreas(List.of(lArena)),
                "nordtal"::equals,
                area -> captureBox(area, world, structures, statePayloads),
                () -> {}));
        assertTrue(file.exists());

        world.put(key("nordtal", 1, 64, 1), new StoredBlock("minecraft:air", ""));
        world.put(key("nordtal", 4, 65, 4), new StoredBlock("minecraft:obsidian", ""));
        world.put(key("nordtal", 0, 64, 4), new StoredBlock("minecraft:diamond_block", "{Items:[]}"));
        world.put(key("nordtal", 1, 66, 1), new StoredBlock("minecraft:gold_block", ""));
        world.put(key("nordtal", 1, 63, 1), new StoredBlock("minecraft:emerald_block", ""));

        final AtomicReference<PolygonAreas> cleaned = new AtomicReference<>();
        copy.restore(
                "nordtal"::equals,
                bytes -> structures.get(new String(bytes, StandardCharsets.UTF_8)),
                (w, x, y, z, state) -> world.put(key(w, x, y, z), statePayloads.get(state)),
                cleaned::set);

        assertFalse(file.exists(), "snapshot file is deleted after restore");
        assertNotNull(cleaned.get());
        assertEquals(1, cleaned.get().all().size());
        assertEquals(
                new StoredBlock("minecraft:oak_sign", "{front_text:\"Fight!\"}"), world.get(key("nordtal", 1, 64, 1)));
        assertEquals(new StoredBlock("minecraft:stone", ""), world.get(key("nordtal", 4, 65, 4)));
        assertEquals(new StoredBlock("minecraft:diamond_block", "{Items:[]}"), world.get(key("nordtal", 0, 64, 4)));
        assertEquals(new StoredBlock("minecraft:gold_block", ""), world.get(key("nordtal", 1, 66, 1)));
        assertEquals(new StoredBlock("minecraft:emerald_block", ""), world.get(key("nordtal", 1, 63, 1)));
    }

    private static Map<String, StoredBlock> stoneCube() {
        final Map<String, StoredBlock> world = new HashMap<>();
        for (int x = 0; x <= 4; x++) {
            for (int y = 63; y <= 66; y++) {
                for (int z = 0; z <= 4; z++) {
                    world.put(key("nordtal", x, y, z), new StoredBlock("minecraft:stone", ""));
                }
            }
        }
        return world;
    }

    private static byte[] captureBox(
            final PolygonArea area,
            final Map<String, StoredBlock> world,
            final Map<String, List<BlockState>> structures,
            final Map<BlockState, StoredBlock> statePayloads) {
        final String id = "struct-" + structures.size();
        final List<BlockState> states = new ArrayList<>();
        for (int x = area.minX(); x <= area.maxX(); x++) {
            for (int y = area.minY(); y <= area.maxY(); y++) {
                for (int z = area.minZ(); z <= area.maxZ(); z++) {
                    final BlockState state = fakeBlockState(x - area.minX(), y - area.minY(), z - area.minZ());
                    statePayloads.put(state, world.get(key(area.world(), x, y, z)));
                    states.add(state);
                }
            }
        }
        structures.put(id, states);
        return id.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void overlappingPolygonsDeduplicateRestoredBlocks() {
        final PolygonArea first = new PolygonArea(
                "A",
                "nordtal",
                List.of(new Corner(0, 0), new Corner(2, 0), new Corner(2, 2), new Corner(0, 2)),
                64,
                64);
        final PolygonArea second = new PolygonArea(
                "B",
                "nordtal",
                List.of(new Corner(1, 0), new Corner(3, 0), new Corner(3, 2), new Corner(1, 2)),
                64,
                64);

        final List<BlockState> firstPalette = gridStates(3, 1, 3);
        final List<BlockState> secondPalette = gridStates(3, 1, 3);
        final Set<ArenaCopy.BlockKey> seen = new HashSet<>();
        final AtomicInteger placed = new AtomicInteger();

        ArenaCopy.restoreArea(first, firstPalette, seen, (w, x, y, z, state) -> placed.incrementAndGet());
        ArenaCopy.restoreArea(second, secondPalette, seen, (w, x, y, z, state) -> placed.incrementAndGet());

        // 4 columns (x=0..3) x 3 columns (z=0..2) x 1 height = 12 unique blocks, not 18.
        assertEquals(12, placed.get());
    }

    @Test
    void leftoverLegacyNbtFileIsCleanedUpOnRestore() throws IOException {
        final File legacy = tempDir.resolve(ArenaCopy.LEGACY_FILE).toFile();
        Files.writeString(legacy.toPath(), "leftover-pre-deploy-structure", StandardCharsets.UTF_8);
        assertTrue(legacy.exists());

        final ArenaCopy copy = new ArenaCopy(
                Logger.getLogger("test"), tempDir.resolve(ArenaCopy.FILE).toFile());
        copy.restore("nordtal"::equals, bytes -> List.of(), (w, x, y, z, state) -> {}, areas -> {});

        assertFalse(legacy.exists(), "leftover duel-arena.nbt is deleted on restore so it is not orphaned");
    }

    private static List<BlockState> gridStates(final int sizeX, final int sizeY, final int sizeZ) {
        final List<BlockState> states = new ArrayList<>();
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    states.add(fakeBlockState(x, y, z));
                }
            }
        }
        return states;
    }

    @SuppressWarnings("ReferenceEquality")
    private static BlockState fakeBlockState(final int x, final int y, final int z) {
        return (BlockState) Proxy.newProxyInstance(
                BlockState.class.getClassLoader(),
                new Class<?>[] {BlockState.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getX" -> x;
                    case "getY" -> y;
                    case "getZ" -> z;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "FakeBlockState(" + x + "," + y + "," + z + ")";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static String key(final String world, final int x, final int y, final int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }
}
