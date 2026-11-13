package eu.nordtal.season.underground.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.underground.anvil.Nbt;
import eu.nordtal.season.underground.anvil.NbtList;
import eu.nordtal.season.underground.anvil.Region;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifyTest {

    private static final List<String> COMPLAINT = List.of(
            "[23:33:42 WARN]: [net.minecraft.world.level.block.entity.BlockEntity] Serialization errors:",
            "minecraft:mob_spawner // net.minecraft.world.level.block.entity.SpawnerBlockEntity@BlockPos{x=20, y=70,"
                    + " z=-3}:",
            "  Failed to decode value '{id:\"minecraft:bee\"}' from field 'SpawnData': No key entity");

    @Test
    void aChunkIsLitWhenTheGameOrPaperMarksItSo() {
        assertTrue(Verify.lit(Map.of("isLightOn", (byte) 1)));
        assertTrue(Verify.lit(Map.of("isLightOn", (byte) 0, "starlight.light_version", 10)));
        assertFalse(Verify.lit(Map.of("isLightOn", (byte) 0)));
    }

    @Test
    void theLinesBelowALineWithATimeBelongToIt() {
        final List<List<String>> entries =
                Verify.entries(List.of("[23:33:41 INFO]: Done", COMPLAINT.get(0), COMPLAINT.get(1), COMPLAINT.get(2)));

        assertEquals(2, entries.size());
        assertEquals(COMPLAINT, entries.get(1));
    }

    @Test
    void aComplaintAboutABlockEntityTheMapHoldsAsItIsIsTheMaps(@TempDir final Path directory) throws IOException {
        final Path map = world(directory.resolve("map"), spawner("minecraft:bee"));

        assertTrue(Verify.inherited(COMPLAINT, map, world(directory.resolve("same"), spawner("minecraft:bee"))));
        assertFalse(Verify.inherited(COMPLAINT, map, world(directory.resolve("changed"), spawner("minecraft:pig"))));
        assertFalse(Verify.inherited(COMPLAINT.subList(1, 3), map, map));
    }

    private static Map<String, Object> spawner(final String entity) {
        final Map<String, Object> spawner = new LinkedHashMap<>();
        spawner.put("id", "minecraft:mob_spawner");
        spawner.put("x", 20);
        spawner.put("y", 70);
        spawner.put("z", -3);
        spawner.put("SpawnData", new LinkedHashMap<>(Map.of("id", entity)));
        spawner.put("Light", new byte[] {1, 2});
        return spawner;
    }

    /** Writes a world whose chunk 1, -1 holds the block entity. */
    private static Path world(final Path directory, final Map<String, Object> entity) throws IOException {
        final NbtList entities = new NbtList(Nbt.COMPOUND);
        entities.add(entity);
        final Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("xPos", 1);
        chunk.put("zPos", -1);
        chunk.put("block_entities", entities);
        Region.write(
                Region.file(directory.resolve("region"), 0, -1), new TreeMap<>(Map.of(1 + 31 * Region.SIDE, chunk)));
        return directory;
    }
}
