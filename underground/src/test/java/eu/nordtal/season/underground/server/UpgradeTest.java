package eu.nordtal.season.underground.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.nordtal.season.underground.anvil.Region;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpgradeTest {

    private static final int NEWEST = 5023;

    @Test
    void onlyTheChunksSentToTheGameComeBackAndAChunkItMadeIsDropped(@TempDir final Path directory) throws IOException {
        final Path target = directory.resolve("r.0.0.mca");
        final Path scratch = directory.resolve("scratch.mca");
        Region.write(target, chunks(Map.of(5, NEWEST)));
        Region.write(scratch, chunks(Map.of(1, NEWEST, 2, 2860, 3, NEWEST)));
        final Upgrade.Pending pending = new Upgrade.Pending(target, Set.of(1, 2));

        assertFalse(Upgrade.merged(scratch, pending, NEWEST));

        assertEquals(Set.of(1, 5), Region.read(target).keySet());
        assertEquals(Set.of(2), Region.read(scratch).keySet());
        assertEquals(Set.of(2), pending.indices());
    }

    private static Map<Integer, Map<String, Object>> chunks(final Map<Integer, Integer> versions) {
        final Map<Integer, Map<String, Object>> chunks = new TreeMap<>();
        versions.forEach((index, version) -> chunks.put(index, Map.of("DataVersion", version)));
        return chunks;
    }
}
