package eu.nordtal.season.smp.rail;

import static eu.nordtal.season.smp.rail.Track.at;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RailGraphTest {

    private static RailGraph graph(final Track track, final RailPos... stations) {
        final RailWalk walk = new RailWalk(track, List.of(stations), 10_000);
        while (!walk.step(1_000)) {
            // The track is readable everywhere, so every step makes progress.
        }
        assertEquals(1, walk.found().size(), "the stations are one network");
        return RailGraph.of(walk.found().getFirst().rails(), List.of(stations));
    }

    @Test
    void aStraightTrackBetweenTwoStationsIsOneEdge() {
        final RailGraph graph = graph(new Track().eastWest(0, 5, 64, 0), at(0, 64, 0), at(5, 64, 0));

        assertEquals(Set.of(), graph.junctions());
        assertEquals(1, graph.edges().size());
        final RailEdge edge = graph.edges().getFirst();
        assertEquals(5, edge.length());
        assertEquals(List.of(at(1, 64, 0), at(2, 64, 0), at(3, 64, 0), at(4, 64, 0)), edge.rails());
    }

    @Test
    void aTJunctionJoinsItsThreeNeighbours() {
        final Track track = new Track().eastWest(-3, 3, 64, 0).northSouth(0, 64, 1, 3);

        final RailGraph graph = graph(track, at(-3, 64, 0), at(3, 64, 0), at(0, 64, 3));

        assertEquals(Set.of(at(0, 64, 0)), graph.junctions());
        assertEquals(3, graph.edges().size());
        assertEquals(
                Set.of(3), graph.edges().stream().map(RailEdge::length).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void aSlopeJoinsTheTrackAboveIt() {
        final Track track = new Track()
                .eastWest(0, 1, 64, 0)
                .rail(2, 64, 0, RailShape.ASCENDING_EAST)
                .eastWest(3, 4, 65, 0);

        final RailGraph graph = graph(track, at(0, 64, 0), at(4, 65, 0));

        assertEquals(1, graph.edges().size());
        assertEquals(4, graph.edges().getFirst().length());
    }

    @Test
    void aSpurThatEndsWithoutAStationIsNoEdge() {
        final Track track = new Track().eastWest(-3, 3, 64, 0).northSouth(0, 64, 1, 3);

        final RailGraph graph = graph(track, at(-3, 64, 0), at(3, 64, 0));

        assertEquals(Set.of(at(0, 64, 0)), graph.junctions());
        assertEquals(2, graph.edges().size());
    }

    @Test
    void aLoopWithOneStationIsOneEdge() {
        final Track track = new Track()
                .rail(0, 64, 0, RailShape.EAST_WEST)
                .rail(1, 64, 0, RailShape.SOUTH_WEST)
                .rail(1, 64, 1, RailShape.NORTH_WEST)
                .rail(0, 64, 1, RailShape.EAST_WEST)
                .rail(-1, 64, 1, RailShape.NORTH_EAST)
                .rail(-1, 64, 0, RailShape.SOUTH_EAST);

        final RailGraph graph = graph(track, at(0, 64, 0));

        assertEquals(1, graph.edges().size());
        assertEquals(6, graph.edges().getFirst().length());
    }
}
