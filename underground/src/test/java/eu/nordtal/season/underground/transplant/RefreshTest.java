package eu.nordtal.season.underground.transplant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.underground.anvil.Region;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
        assertEquals(Set.of(new Refresh.Spot(1, 0)), changes.regions());
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
        assertTrue(changes.regions().isEmpty());
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
    void theRegionsAroundEachChangeAreTransplantedAgainWithARingForTheirEntrances() {
        final Refresh.Changes changes =
                new Refresh.Changes(2, new TreeSet<>(List.of(new Refresh.Spot(0, 0), new Refresh.Spot(15, 15))));

        assertEquals(2 * 25, changes.affected().size());
        assertEquals(2 * 49, changes.run().size());
        assertTrue(changes.affected().contains(new Refresh.Spot(-2, 2)));
        assertTrue(changes.affected().contains(new Refresh.Spot(17, 13)));
        assertFalse(changes.affected().contains(new Refresh.Spot(7, 7)));
        assertTrue(changes.run().contains(new Refresh.Spot(-3, 3)));
        assertEquals("-3,-3;-3,-2", Refresh.listed(changes.run()).substring(0, 11));
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
