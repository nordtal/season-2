package eu.nordtal.season.underground.transplant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.season.underground.anvil.Region;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RefreshTest {

    @Test
    void aChunkSavedSinceTheEarlierCopyReplacesItsUpgradedStateAndTheOthersStayUpgraded(@TempDir final Path directory)
            throws IOException {
        write(directory.resolve("before"), Map.of(1, chunk(2860, "old"), 2, chunk(5023, "built")));
        write(directory.resolve("upgraded"), Map.of(1, chunk(5023, "old"), 2, chunk(5023, "built")));
        write(directory.resolve("now"), Map.of(1, chunk(2860, "old"), 2, chunk(5023, "built more")));

        final Refresh.Changes changes = refresh(directory);

        final Map<Integer, Map<String, Object>> result =
                Region.read(Region.file(directory.resolve("out/region"), 1, 0));
        assertEquals(5023, result.get(1).get("DataVersion"));
        assertEquals("built more", result.get(2).get("contents"));
        assertEquals(1, changes.chunks());
        assertEquals(new Refresh.Bounds(34, 0, 34, 0), changes.bounds());
        assertEquals(
                "built",
                Region.read(Region.file(directory.resolve("upgraded/region"), 1, 0))
                        .get(2)
                        .get("contents"));
    }

    @Test
    void nothingSavedSinceTheEarlierCopyLeavesTheUpgradedMap(@TempDir final Path directory) throws IOException {
        write(directory.resolve("before"), Map.of(1, chunk(2860, "old")));
        write(directory.resolve("upgraded"), Map.of(1, chunk(5023, "old")));
        write(directory.resolve("now"), Map.of(1, chunk(2860, "old")));

        final Refresh.Changes changes = refresh(directory);

        assertEquals(0, changes.chunks());
        assertNull(changes.bounds());
        assertEquals(
                5023,
                Region.read(Region.file(directory.resolve("out/region"), 1, 0))
                        .get(1)
                        .get("DataVersion"));
    }

    @Test
    void aChangedChunkOfAnOlderVersionIsRefused(@TempDir final Path directory) throws IOException {
        write(directory.resolve("before"), Map.of(1, chunk(2860, "old")));
        write(directory.resolve("upgraded"), Map.of(1, chunk(5023, "old")));
        write(directory.resolve("now"), Map.of(1, chunk(2860, "changed")));

        assertThrows(IOException.class, () -> refresh(directory));
    }

    @Test
    void theRegionsAroundAChangeAreTransplantedAgainWithARingForTheirEntrances() {
        final Refresh.Bounds bounds = new Refresh.Bounds(40, -3, 70, 5);

        assertArrayEquals(new int[] {-1, -3, 4, 2}, bounds.affected());
        assertArrayEquals(new int[] {-2, -4, 5, 3}, bounds.run());
    }

    private static Refresh.Changes refresh(final Path directory) throws IOException {
        return Refresh.run(
                directory.resolve("before"),
                directory.resolve("now"),
                directory.resolve("upgraded"),
                directory.resolve("out"));
    }

    private static void write(final Path world, final Map<Integer, Map<String, Object>> chunks) throws IOException {
        Region.write(Region.file(world.resolve("region"), 1, 0), new TreeMap<>(chunks));
    }

    private static Map<String, Object> chunk(final int version, final String contents) {
        final Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("DataVersion", version);
        chunk.put("contents", contents);
        return chunk;
    }
}
