package eu.nordtal.season.underground.structures;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClustersTest {

    @Test
    void positionsJoinThroughAChainAndAFarOneStandsAlone() {
        final List<Clusters.At<String>> items = List.of(
                new Clusters.At<>(0, 60, 0, "a"),
                new Clusters.At<>(40, 70, 0, "b"),
                new Clusters.At<>(80, 50, 0, "c"),
                new Clusters.At<>(300, 60, 300, "d"));

        final List<List<Clusters.At<String>>> groups = Clusters.of(items, 48);

        assertEquals(2, groups.size());
        assertEquals(
                List.of("a", "b", "c"),
                groups.getFirst().stream().map(Clusters.At::thing).toList());
        assertEquals(new Box(0, 50, 0, 80, 70, 0), Clusters.box(groups.getFirst()));
    }
}
