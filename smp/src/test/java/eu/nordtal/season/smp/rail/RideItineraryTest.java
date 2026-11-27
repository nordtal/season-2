package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Tests travel-order itinerary construction, switch counting, hidden station omission, and small-font checks. */
class RideItineraryTest {

    private static StationRow station(final UUID id, final int x, final int z, final String name) {
        return new StationRow(id, "world", x, 64, z, "EAST_WEST", UUID.randomUUID(), name, Instant.EPOCH);
    }

    @Test
    void buildsPassedStationsAndSwitchesInTravelOrderAndOmitsHiddenIntermediates() {
        final RailPos originPos = new RailPos(0, 64, 0);
        final RailPos junctionPos = new RailPos(2, 64, 0);
        final RailPos midVisiblePos = new RailPos(4, 64, 0);
        final RailPos midHiddenPos = new RailPos(6, 64, 0);
        final RailPos destPos = new RailPos(8, 64, 0);

        final UUID originId = new UUID(0, 1);
        final UUID midVisibleId = new UUID(0, 2);
        final UUID midHiddenId = new UUID(0, 3);
        final UUID destId = new UUID(0, 4);

        final RailEdge e1 = RailEdge.of(originPos, junctionPos, List.of(new RailPos(1, 64, 0)));
        final RailEdge e2 = RailEdge.of(junctionPos, midVisiblePos, List.of(new RailPos(3, 64, 0)));
        final RailEdge e3 = RailEdge.of(midVisiblePos, midHiddenPos, List.of(new RailPos(5, 64, 0)));
        final RailEdge e4 = RailEdge.of(midHiddenPos, destPos, List.of(new RailPos(7, 64, 0)));
        final RailEdge branch = RailEdge.of(junctionPos, new RailPos(2, 64, 2), List.of(new RailPos(2, 64, 1)));

        final RailGraph graph = new RailGraph(
                List.of(originPos, midVisiblePos, midHiddenPos, destPos),
                List.of(junctionPos),
                List.of(e1, e2, e3, e4, branch));

        final List<StationRow> stations = List.of(
                station(originId, 0, 0, "Spawn"),
                station(midVisibleId, 4, 0, "Market"),
                station(midHiddenId, 6, 0, "Secret"),
                station(destId, 8, 0, "Harbour"));

        final Optional<RideItinerary.Plan> plan =
                RideItinerary.of(graph, originPos, stations, Set.of(midHiddenId), destId, "Spawn", "Current track");

        assertTrue(plan.isPresent());
        assertEquals("Spawn", plan.get().originName());
        assertTrue(plan.get().atStation());
        assertEquals("Harbour", plan.get().destinationName());
        assertEquals(8, plan.get().distance());
        assertEquals(1, plan.get().passed().size());
        assertEquals(midVisibleId, plan.get().passed().getFirst().station());
        assertEquals("Market", plan.get().passed().getFirst().name());
        assertEquals(1, plan.get().passed().getFirst().switchesBefore());
        assertEquals(0, plan.get().switchesBeforeDestination());
    }

    @Test
    void usesTrackLabelWhenCartStandsBetweenStationsAndRecomputesFromCurrentPosition() {
        final RailPos aPos = new RailPos(0, 64, 0);
        final RailPos midTrack = new RailPos(2, 64, 0);
        final RailPos bPos = new RailPos(4, 64, 0);
        final UUID aId = new UUID(0, 1);
        final UUID bId = new UUID(0, 2);

        final RailGraph graph = RailGraph.of(
                Map.of(
                        aPos,
                        RailShape.EAST_WEST,
                        new RailPos(1, 64, 0),
                        RailShape.EAST_WEST,
                        midTrack,
                        RailShape.EAST_WEST,
                        new RailPos(3, 64, 0),
                        RailShape.EAST_WEST,
                        bPos,
                        RailShape.EAST_WEST),
                List.of(aPos, bPos));

        final List<StationRow> stations = List.of(station(aId, 0, 0, "Alpha"), station(bId, 4, 0, "Beta"));
        final Optional<RideItinerary.Plan> plan =
                RideItinerary.of(graph, midTrack, stations, Set.of(), bId, "Spawn", "Current track");

        assertTrue(plan.isPresent());
        assertEquals("Current track", plan.get().originName());
        assertFalse(plan.get().atStation());
        assertEquals(2, plan.get().distance());
        assertEquals(List.of(), plan.get().passed());
    }

    @Test
    void detectsWhetherAStationNameFitsTheFivePixelSmallFont() {
        assertTrue(RideItinerary.fitsSmallFont("Harbour"));
        assertTrue(RideItinerary.fitsSmallFont("Nordtal Ost"));
        assertTrue(RideItinerary.fitsSmallFont("Mühle 3"));
        assertFalse(RideItinerary.fitsSmallFont("Station ★"));
    }
}
