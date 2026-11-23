package eu.nordtal.season.smp.duel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.inbox.AreaShape.Corner;
import eu.nordtal.season.smp.region.PolygonArea;
import eu.nordtal.season.smp.region.PolygonAreas;
import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArenaCopyTest {

    @TempDir
    Path tempDir;

    @Test
    void snapshotAndRestoreOnlyTouchBlocksInsideThe3dPolygon() {
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
        final PolygonAreas areas = new PolygonAreas(List.of(lArena));

        final Map<String, String> world = new HashMap<>();
        for (int x = 0; x <= 4; x++) {
            for (int y = 63; y <= 66; y++) {
                for (int z = 0; z <= 4; z++) {
                    world.put(key("nordtal", x, y, z), "minecraft:stone");
                }
            }
        }

        final File file = tempDir.resolve(ArenaCopy.FILE).toFile();
        final ArenaCopy copy = new ArenaCopy(Logger.getLogger("test"), file);

        assertTrue(copy.save(areas, "nordtal"::equals, (w, x, y, z) -> world.get(key(w, x, y, z)), () -> {}));
        assertTrue(file.exists());

        // Mutate blocks inside the polygon, in the L-notch (outside polygon but inside bounding box), and above maxY.
        world.put(key("nordtal", 1, 64, 1), "minecraft:oak_planks");
        world.put(key("nordtal", 4, 65, 4), "minecraft:obsidian");
        world.put(key("nordtal", 0, 64, 4), "minecraft:diamond_block"); // notch: outside polygon!
        world.put(key("nordtal", 1, 66, 1), "minecraft:gold_block"); // above maxY: outside polygon!
        world.put(key("nordtal", 1, 63, 1), "minecraft:emerald_block"); // below minY: outside polygon!

        final AtomicReference<PolygonAreas> cleaned = new AtomicReference<>();
        copy.restore("nordtal"::equals, (w, x, y, z, data) -> world.put(key(w, x, y, z), data), cleaned::set);

        assertFalse(file.exists(), "snapshot file is deleted after restore");
        assertNotNull(cleaned.get());
        assertEquals(1, cleaned.get().all().size());

        // Inside polygon (including east/south corner (4, 65, 4)) -> restored to stone.
        assertEquals("minecraft:stone", world.get(key("nordtal", 1, 64, 1)));
        assertEquals("minecraft:stone", world.get(key("nordtal", 4, 65, 4)));

        // Outside polygon (notch, above maxY, below minY) -> untouched!
        assertEquals("minecraft:diamond_block", world.get(key("nordtal", 0, 64, 4)));
        assertEquals("minecraft:gold_block", world.get(key("nordtal", 1, 66, 1)));
        assertEquals("minecraft:emerald_block", world.get(key("nordtal", 1, 63, 1)));
    }

    @Test
    void overlappingPolygonsDeduplicateBlocksAndRestoreCleanly() {
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
        final ArenaCopy.Snapshot snapshot =
                ArenaCopy.capture(new PolygonAreas(List.of(first, second)), (w, x, y, z) -> "minecraft:air");

        // 4 columns (x=0..3) x 3 columns (z=0..2) x 1 height = 12 unique blocks, not 18.
        assertEquals(12, snapshot.blocks().size());
    }

    private static String key(final String world, final int x, final int y, final int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }
}
