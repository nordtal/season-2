package eu.nordtal.season.smp.rail;

import static eu.nordtal.season.smp.rail.Track.at;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StationAxisTest {

    private static final RailPos HERE = at(0, 64, 0);

    private static RailShape place(final Map<RailPos, RailShape> rails, final Heading facing) {
        return StationAxis.of(HERE, rails::get, facing);
    }

    @Test
    void aTrackThatLeadsHereDecidesOverThePlacersFacing() {
        assertEquals(RailShape.NORTH_SOUTH, place(Map.of(at(0, 64, -1), RailShape.NORTH_SOUTH), Heading.EAST));
        assertEquals(
                RailShape.EAST_WEST,
                place(Map.of(at(-1, 64, 0), RailShape.EAST_WEST, at(1, 64, 0), RailShape.EAST_WEST), Heading.SOUTH));
    }

    @Test
    void aParallelTrackBesideTheStationDoesNotCount() {
        assertEquals(RailShape.NORTH_SOUTH, place(Map.of(at(1, 64, 0), RailShape.NORTH_SOUTH), Heading.NORTH));
    }

    @Test
    void aSlopeCountsOnlyWhenItRisesOrDescendsToTheStation() {
        assertEquals(RailShape.NORTH_SOUTH, place(Map.of(at(0, 63, -1), RailShape.ASCENDING_SOUTH), Heading.EAST));
        assertEquals(RailShape.NORTH_SOUTH, place(Map.of(at(0, 64, -1), RailShape.ASCENDING_NORTH), Heading.EAST));
        assertEquals(RailShape.EAST_WEST, place(Map.of(at(0, 63, -1), RailShape.ASCENDING_NORTH), Heading.EAST));
    }

    @Test
    void theAxisWithRailsOnBothSidesWinsOverAStub() {
        assertEquals(
                RailShape.NORTH_SOUTH,
                place(
                        Map.of(
                                at(0, 64, -1), RailShape.NORTH_SOUTH,
                                at(0, 64, 1), RailShape.NORTH_SOUTH,
                                at(1, 64, 0), RailShape.EAST_WEST),
                        Heading.EAST));
    }

    @Test
    void withoutATrackOrWithATieThePlacersFacingDecides() {
        assertEquals(RailShape.EAST_WEST, place(Map.of(), Heading.WEST));
        assertEquals(
                RailShape.NORTH_SOUTH,
                place(Map.of(at(1, 64, 0), RailShape.EAST_WEST, at(0, 64, -1), RailShape.NORTH_SOUTH), Heading.SOUTH));
    }
}
