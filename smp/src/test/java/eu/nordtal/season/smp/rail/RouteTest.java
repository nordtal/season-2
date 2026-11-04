package eu.nordtal.season.smp.rail;

import static eu.nordtal.season.smp.rail.Track.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RouteTest {

    private static final RailPos WEST = at(-3, 64, 0);
    private static final RailPos EAST = at(3, 64, 0);
    private static final RailPos SOUTH = at(0, 64, 3);
    private static final RailPos JUNCTION = at(0, 64, 0);

    private static RailGraph graph(final Track track, final RailPos... stations) {
        return RailGraph.of(track.rails(), List.of(stations));
    }

    private static RailGraph junction() {
        return graph(new Track().eastWest(-3, 3, 64, 0).northSouth(0, 64, 1, 3), WEST, EAST, SOUTH);
    }

    @Test
    void aRouteThroughAJunctionTurnsItTowardsTheNextLeg() {
        final RailGraph graph = junction();

        final Route route = Route.between(graph, WEST, SOUTH).orElseThrow();

        assertEquals(2, route.legs().size());
        assertEquals(6, route.length());
        assertEquals(Map.of(route.rails().indexOf(JUNCTION), RailShape.SOUTH_WEST), route.switches(graph.junctions()));
    }

    @Test
    void aRouteStraightOnSetsTheJunctionStraight() {
        final RailGraph graph = junction();

        final Route route = Route.between(graph, WEST, EAST).orElseThrow();

        assertEquals(Map.of(route.rails().indexOf(JUNCTION), RailShape.EAST_WEST), route.switches(graph.junctions()));
    }

    @Test
    void theShorterOfTwoWaysIsTaken() {
        // A short way along z 0, and a long one that leaves at x 0 and comes back at x 6.
        final Track track = new Track()
                .eastWest(-3, 9, 64, 0)
                .northSouth(0, 64, 1, 4)
                .northSouth(6, 64, 1, 4)
                .rail(0, 64, 5, RailShape.NORTH_EAST)
                .eastWest(1, 5, 64, 5)
                .rail(6, 64, 5, RailShape.NORTH_WEST);
        final RailGraph graph = graph(track, at(-3, 64, 0), at(9, 64, 0));

        final Route route = Route.between(graph, at(-3, 64, 0), at(9, 64, 0)).orElseThrow();

        assertEquals(12, route.length());
    }

    @Test
    void aRoutePassesThroughAStationAlongIt() {
        final RailGraph graph = graph(new Track().eastWest(0, 10, 64, 0), at(0, 64, 0), at(5, 64, 0), at(10, 64, 0));

        final Route route = Route.between(graph, at(0, 64, 0), at(10, 64, 0)).orElseThrow();

        assertEquals(2, route.legs().size());
        assertEquals(Map.of(), route.switches(graph.junctions()));
    }

    @Test
    void aStationOutsideTheNetworkHasNoRoute() {
        assertTrue(Route.between(junction(), WEST, at(50, 64, 50)).isEmpty());
    }

    @Test
    void theDistancesCountTheStepsAlongTheRails() {
        final Map<RailPos, Integer> distances = Route.distances(junction(), WEST);

        assertEquals(6, distances.get(EAST));
        assertEquals(6, distances.get(SOUTH));
        assertEquals(3, distances.get(JUNCTION));
    }

    @Test
    void aRouteListsEveryRailFromTheStartToTheStation() {
        final Route route = Route.between(junction(), WEST, SOUTH).orElseThrow();

        assertEquals(
                List.of(WEST, at(-2, 64, 0), at(-1, 64, 0), JUNCTION, at(0, 64, 1), at(0, 64, 2), SOUTH),
                route.rails());
    }

    @Test
    void aStationIsNeverLeftOrReachedFromTheSide() {
        // The track south leads into the side of a station rail that runs east to west.
        final Track track = new Track().eastWest(-3, 3, 64, 0).northSouth(0, 64, 1, 3);
        final RailGraph graph = graph(track, WEST, EAST, at(0, 64, 0), SOUTH);

        assertTrue(Route.between(graph, at(0, 64, 0), SOUTH).isEmpty());
        assertTrue(Route.between(graph, WEST, SOUTH).isEmpty());
        assertEquals(6, Route.between(graph, WEST, EAST).orElseThrow().length());
    }

    @Test
    void aJunctionTakesNoTurnOntoARailOneBlockHigher() {
        // The junction rises towards the south, where the branch runs on one block higher.
        final Track track = new Track()
                .eastWest(-3, 3, 64, 0)
                .rail(0, 64, 0, RailShape.ASCENDING_SOUTH)
                .northSouth(0, 65, 1, 3);
        final RailGraph graph = graph(track, WEST, EAST, at(0, 65, 3));

        assertEquals(Set.of(JUNCTION), graph.junctions());
        assertTrue(Route.between(graph, WEST, at(0, 65, 3)).isEmpty());
        final Route straight = Route.between(graph, WEST, EAST).orElseThrow();
        assertEquals(
                Map.of(straight.rails().indexOf(JUNCTION), RailShape.EAST_WEST), straight.switches(graph.junctions()));
    }

    @Test
    void aMovingCartKeepsItsDirection() {
        final RailGraph graph = junction();
        final RailPos between = at(-1, 64, 0);

        assertTrue(Route.between(graph, between, at(-2, 64, 0), EAST).isEmpty(), "it would have to turn back");
        final Route onward = Route.between(graph, between, JUNCTION, EAST).orElseThrow();
        assertEquals(4, onward.length());
        assertEquals(between, onward.rails().getFirst());
        assertEquals(4, Route.between(graph, between, null, EAST).orElseThrow().length());
    }

    @Test
    void aCartNeverTurnsBackAtAJunction() {
        final RailGraph graph = junction();
        final RailEdge west = graph.leaving(JUNCTION).stream()
                .filter(edge -> edge.to().equals(WEST))
                .findFirst()
                .orElseThrow();

        assertFalse(Route.passes(graph, west.reversed(), west));
    }

    @Test
    void aCartOnTrackThatEndsRidesBackToTheNodeItLeaves() {
        final RailGraph graph = graph(new Track().eastWest(-3, 3, 64, 0), WEST);
        final RailPos stub = at(2, 64, 0);

        final Route back = Route.between(graph, stub, WEST).orElseThrow();

        assertEquals(List.of(stub, at(1, 64, 0), at(0, 64, 0), at(-1, 64, 0), at(-2, 64, 0), WEST), back.rails());
        assertTrue(RideList.onTrack(graph, stub));
        assertTrue(Route.between(graph, stub, EAST, WEST).isEmpty(), "the track ends ahead");
        assertEquals(Map.of(WEST, 5), Route.distances(graph, stub));
    }
}
