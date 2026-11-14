package eu.nordtal.season.underground.check;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.underground.anvil.Region;
import eu.nordtal.season.underground.anvil.TestChunks;
import eu.nordtal.season.underground.anvil.TestChunks.BlockAt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Records per cell what the map, the generated world and the result hold there. */
class ComparedTest {

    @TempDir
    Path folder;

    private void world(final String name, final BlockAt blocks) throws IOException {
        Files.createDirectories(folder.resolve(name).resolve("region"));
        Region.write(
                Region.file(folder.resolve(name).resolve("region"), 0, 0), Map.of(0, TestChunks.chunk(0, 0, blocks)));
    }

    private static String ground(final int y) {
        return y < 0 ? "minecraft:stone" : "minecraft:air";
    }

    @Test
    void eachCellKnowsWhereItsBlockComesFrom() throws IOException {
        world("map", (x, y, z) -> x == 1 && y == -10 && z == 1 ? "minecraft:oak_planks" : ground(y));
        world("parallel", (x, y, z) -> x == 2 && y == -10 && z == 2 ? "minecraft:water" : ground(y));
        world(
                "out",
                (x, y, z) -> x == 1 && y == -10 && z == 1
                        ? "minecraft:oak_planks"
                        : x == 2 && y == -10 && z == 2 ? "minecraft:water" : ground(y));

        final byte[] cells =
                new Compared(folder.resolve("map"), folder.resolve("parallel"), folder.resolve("out"), 0, 0).cells;

        assertEquals(Compared.BUILT | Compared.AS_MAP, cells[Compared.index(1, 54, 1)] & 0xFF);
        assertEquals(
                Compared.OPEN | Compared.FLUID | Compared.PARALLEL_OPEN | Compared.AS_PARALLEL,
                cells[Compared.index(2, 54, 2)] & 0xFF);
        assertEquals(Compared.AS_MAP | Compared.AS_PARALLEL, cells[Compared.index(3, 54, 3)] & 0xFF);
        assertEquals(
                Compared.OPEN | Compared.MAP_OPEN | Compared.PARALLEL_OPEN | Compared.AS_MAP | Compared.AS_PARALLEL,
                cells[Compared.index(3, 70, 3)] & 0xFF);
    }
}
