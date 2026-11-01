package eu.nordtal.season.smp.rail;

import static eu.nordtal.season.smp.rail.Track.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class RideListTest {

    private static final Instant NAMED = Instant.parse("2026-11-01T12:00:00Z");

    private static RailGraph graph(final Track track, final RailPos... stations) {
        final RailWalk walk = new RailWalk(track, List.of(stations), 10_000);
        while (!walk.step(1_000)) {
            // Every position is readable.
        }
        return RailGraph.of(walk.found().getFirst().rails(), List.of(stations));
    }

    private static StationRow station(final int id, final RailPos pos, final @Nullable String name) {
        return new StationRow(
                new UUID(0, id),
                "nordtal",
                pos.x(),
                pos.y(),
                pos.z(),
                "EAST_WEST",
                new UUID(1, id),
                name,
                name == null ? null : NAMED);
    }

    private static List<String> names(final List<RideList.Stop> stops) {
        return stops.stream().map(RideList.Stop::name).toList();
    }

    /** A line along x from 0 to 30 with a station every ten blocks. */
    private static final RailGraph LINE =
            graph(new Track().eastWest(0, 30, 64, 0), at(0, 64, 0), at(10, 64, 0), at(20, 64, 0), at(30, 64, 0));

    private static final List<StationRow> STATIONS = List.of(
            station(1, at(0, 64, 0), "Spawn"),
            station(2, at(10, 64, 0), "Mine"),
            station(3, at(20, 64, 0), "Farm"),
            station(4, at(30, 64, 0), "Harbour"));

    @Test
    void theNearestStationsComeFirstAndTheOneTheCartStandsOnIsLeftOut() {
        final List<RideList.Stop> stops = RideList.of(LINE, at(10, 64, 0), STATIONS, Set.of());

        assertEquals(List.of("Farm", "Spawn", "Harbour"), names(stops));
        assertEquals(
                List.of(10, 10, 20), stops.stream().map(RideList.Stop::distance).toList());
    }

    @Test
    void favouritesComeFirstEachByDistance() {
        final List<RideList.Stop> stops =
                RideList.of(LINE, at(0, 64, 0), STATIONS, Set.of(new UUID(0, 4), new UUID(0, 3)));

        assertEquals(List.of("Farm", "Harbour", "Mine"), names(stops));
        assertEquals(
                List.of(true, true, false),
                stops.stream().map(RideList.Stop::favourite).toList());
    }

    @Test
    void aCartBetweenTwoStationsMeasuresAlongTheRailsFromWhereItIs() {
        final List<RideList.Stop> stops = RideList.of(LINE, at(13, 64, 0), STATIONS, Set.of());

        assertEquals(List.of("Mine", "Farm", "Spawn", "Harbour"), names(stops));
        assertEquals(
                List.of(3, 7, 13, 17),
                stops.stream().map(RideList.Stop::distance).toList());
    }

    @Test
    void stationsAtTheSameDistanceGoByNameRegardlessOfCase() {
        final List<StationRow> stations = List.of(station(1, at(0, 64, 0), "spawn"), station(3, at(20, 64, 0), "Farm"));

        assertEquals(List.of("Farm", "spawn"), names(RideList.of(LINE, at(10, 64, 0), stations, Set.of())));
    }

    @Test
    void anUnnamedStationIsNotListed() {
        final List<StationRow> stations = List.of(station(1, at(0, 64, 0), "Spawn"), station(2, at(10, 64, 0), null));

        assertEquals(List.of("Spawn"), names(RideList.of(LINE, at(20, 64, 0), stations, Set.of())));
    }

    @Test
    void aStationTheCartCannotReachIsNotListed() {
        final List<StationRow> stations =
                List.of(station(1, at(0, 64, 0), "Spawn"), station(9, at(50, 64, 50), "Elsewhere"));

        assertEquals(List.of("Spawn"), names(RideList.of(LINE, at(30, 64, 0), stations, Set.of())));
    }

    @Test
    void aCartOnTrackThatLeadsNowhereIsOnNoTrack() {
        final RailGraph spur = graph(new Track().eastWest(0, 10, 64, 0), at(5, 64, 0));

        assertTrue(RideList.onTrack(LINE, at(13, 64, 0)));
        assertFalse(RideList.onTrack(spur, at(8, 64, 0)));
        assertEquals(List.of(), RideList.of(spur, at(8, 64, 0), List.of(station(1, at(5, 64, 0), "Spawn")), Set.of()));
    }
}
