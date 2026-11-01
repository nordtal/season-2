package eu.nordtal.season.smp.rail;

import static eu.nordtal.season.smp.rail.Track.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChunkRetriesTest {

    private static final List<RailPos> STARTS = List.of(at(0, 64, 0), at(40, 64, 0));

    @Test
    void theStartsOfAWalkThatGaveUpAreWalkedAgainWhenTheChunkLoads() {
        final ChunkRetries retries = new ChunkRetries();

        assertTrue(retries.gaveUp(Set.of(7L), STARTS, _ -> false).isEmpty());

        assertEquals(Set.copyOf(STARTS), retries.loaded(7L));
    }

    @Test
    void aChunkThatLoadedWhileTheWalkRanIsWalkedAgainAtOnce() {
        final ChunkRetries retries = new ChunkRetries();

        assertEquals(Set.copyOf(STARTS), retries.gaveUp(Set.of(7L), STARTS, _ -> true));
        assertTrue(retries.loaded(7L).isEmpty(), "nothing is left to wait for it");
    }

    @Test
    void aChunkIsWalkedAgainOnlyOnItsFirstLoad() {
        final ChunkRetries retries = new ChunkRetries();
        retries.gaveUp(Set.of(7L), STARTS, _ -> false);

        retries.loaded(7L);

        assertTrue(retries.loaded(7L).isEmpty());
    }

    @Test
    void anotherChunkLoadingWalksNothing() {
        final ChunkRetries retries = new ChunkRetries();
        retries.gaveUp(Set.of(7L), STARTS, _ -> false);

        assertTrue(retries.loaded(8L).isEmpty());
    }
}
