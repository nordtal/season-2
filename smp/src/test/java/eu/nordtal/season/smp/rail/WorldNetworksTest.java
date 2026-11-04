package eu.nordtal.season.smp.rail;

import static eu.nordtal.season.smp.rail.Track.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class WorldNetworksTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final Track track = new Track();
    private WorldNetworks networks = new WorldNetworks();

    private void station(final int id, final RailPos pos, final @Nullable String name, final int minute) {
        networks.addStation(new StationRow(
                new UUID(0, id),
                "nordtal",
                pos.x(),
                pos.y(),
                pos.z(),
                track.rails().getOrDefault(pos, RailShape.EAST_WEST).name(),
                new UUID(1, id),
                name,
                name == null ? null : NOW.minusSeconds(3_600L - 60L * minute)));
    }

    /** Places a station and re-checks the track around it, as placing one in the world does. */
    private void placed(final int id, final RailPos pos) {
        station(id, pos, null, 0);
        changed(pos);
    }

    private static WorldNetworks.Change ran(final RailRecheck job) {
        for (int steps = 0; steps < 100_000; steps++) {
            if (job.step(1_000, Long.MAX_VALUE)) {
                return job.change();
            }
        }
        throw new AssertionError("the re-check did not end");
    }

    private WorldNetworks.Change changed(final RailPos... positions) {
        final @Nullable RailRecheck job = networks.recheck(List.of(positions), track, CLOCK);
        assertNotNull(job, "the change touches the track the world holds");
        return ran(job);
    }

    private @Nullable String nameAt(final RailPos pos) {
        return networks.station(pos).orElseThrow().name();
    }

    private RailGraph graphAt(final RailPos pos) {
        return networks.networkOf(pos).orElseThrow().graph();
    }

    /** Asserts that every network is the graph a fresh trace of its stations finds, and that none is missing. */
    private void sameAsAFreshTrace() {
        final Set<RailPos> covered = new HashSet<>();
        for (final WorldNetworks.Network network : networks.networks()) {
            final RailGraph held = network.graph();
            final RailGraph fresh = RailGraph.of(track.rails(), held.stations());
            assertEquals(fresh.stations(), held.stations());
            assertEquals(fresh.junctions(), held.junctions(), "the junctions of " + held.stations());
            assertEquals(Set.copyOf(fresh.edges()), Set.copyOf(held.edges()), "the edges of " + held.stations());
            assertEquals(shapes(fresh), shapes(held), "the legs of " + held.stations());
            covered.addAll(held.stations());
        }
        final Set<RailPos> onTrack = networks.stations().stream()
                .map(StationRow::pos)
                .filter(pos -> track.rails().containsKey(pos))
                .collect(Collectors.toSet());
        assertEquals(onTrack, covered, "every station rail on the track is in a network");
    }

    private static Set<List<Object>> shapes(final RailGraph graph) {
        return graph.legs().stream()
                .map(leg -> List.<Object>of(leg.kind(), leg.from(), String.valueOf(leg.to()), leg.rails()))
                .collect(Collectors.toSet());
    }

    @Test
    void aJoiningRailMergesTwoNetworksAndSuffixesTheLaterName() {
        track.eastWest(0, 4, 64, 0).eastWest(6, 10, 64, 0);
        station(1, at(0, 64, 0), "Spawn", 0);
        station(2, at(10, 64, 0), "Spawn", 5);
        changed(at(0, 64, 0));
        changed(at(10, 64, 0));
        assertEquals(2, networks.networks().size());

        track.rail(5, 64, 0, RailShape.EAST_WEST);
        final WorldNetworks.Change merged = changed(at(5, 64, 0));

        assertEquals(1, networks.networks().size());
        assertEquals(List.of(), merged.removed(), "track that ended between them is not stored");
        assertEquals(1, merged.added().size());
        assertEquals("Spawn", nameAt(at(0, 64, 0)));
        assertEquals("Spawn 2", nameAt(at(10, 64, 0)));
        assertEquals(
                List.of(new UUID(0, 2)),
                merged.renamed().stream().map(StationRow::id).toList());
        sameAsAFreshTrace();
    }

    @Test
    void aBrokenRailSplitsANetworkInTwo() {
        track.eastWest(0, 10, 64, 0);
        station(1, at(0, 64, 0), "West", 0);
        station(2, at(10, 64, 0), "East", 1);
        changed(at(0, 64, 0));
        assertEquals(1, networks.networks().size());
        final RailGraph before = graphAt(at(0, 64, 0));

        track.remove(5, 64, 0);
        final WorldNetworks.Change split = changed(at(5, 64, 0));

        assertEquals(2, networks.networks().size());
        assertFalse(graphAt(at(0, 64, 0)).stations().contains(at(10, 64, 0)));
        assertEquals(1, split.removed().size());
        assertEquals(List.of(), split.added());
        assertFalse(before.current(), "a ride on the old graph plans again");
        assertTrue(networks.networkOf(at(3, 64, 0)).isPresent(), "the rails left over are still the west's spur");
        assertTrue(networks.networkOf(at(5, 64, 0)).isEmpty());
        sameAsAFreshTrace();
    }

    @Test
    void aRailPlacedBetweenTwoJunctionsMergesTheirNetworks() {
        // Two T junctions, each with two stations, joined by one rail.
        track.eastWest(0, 9, 64, 0)
                .northSouth(3, 64, 1, 3)
                .eastWest(11, 20, 64, 0)
                .northSouth(17, 64, 1, 3);
        placed(1, at(0, 64, 0));
        placed(2, at(3, 64, 3));
        placed(3, at(20, 64, 0));
        placed(4, at(17, 64, 3));
        assertEquals(2, networks.networks().size());

        track.rail(10, 64, 0, RailShape.EAST_WEST);
        changed(at(10, 64, 0));

        assertEquals(1, networks.networks().size());
        assertEquals(Set.of(at(3, 64, 0), at(17, 64, 0)), graphAt(at(0, 64, 0)).junctions());
        assertTrue(Route.between(graphAt(at(0, 64, 0)), at(3, 64, 3), at(17, 64, 3))
                .isPresent());
        sameAsAFreshTrace();
    }

    @Test
    void aBranchThatIsBuiltMakesAJunctionAndOneThatIsBrokenTakesItAway() {
        track.eastWest(0, 20, 64, 0);
        placed(1, at(0, 64, 0));
        placed(2, at(20, 64, 0));
        final UUID straight = networks.legs().iterator().next().id();

        track.northSouth(10, 64, 1, 5);
        final WorldNetworks.Change branched = changed(at(10, 64, 1));
        placed(3, at(10, 64, 5));

        assertEquals(Set.of(at(10, 64, 0)), branched.junctions());
        assertEquals(
                List.of(straight), branched.removed().stream().map(RailLeg::id).toList());
        assertEquals(2, branched.added().size(), "the straight track is two edges now, the branch a spur");
        assertEquals(Set.of(at(10, 64, 0)), graphAt(at(0, 64, 0)).junctions());
        assertEquals(3, graphAt(at(0, 64, 0)).edges().size());
        sameAsAFreshTrace();

        track.remove(10, 64, 1);
        final WorldNetworks.Change broken = changed(at(10, 64, 1));

        assertEquals(Set.of(at(10, 64, 0)), broken.crossings());
        assertEquals(Set.of(), graphAt(at(0, 64, 0)).junctions());
        assertEquals(2, networks.networks().size(), "the branch's station is a network of its own");
        sameAsAFreshTrace();
    }

    @Test
    void anEdgeThatChangesItsCourseIsReplacedAndTheOthersAreKept() {
        // West to middle to east; the track between middle and east takes a detour once a rail turns.
        track.eastWest(0, 40, 64, 0);
        placed(1, at(0, 64, 0));
        placed(2, at(20, 64, 0));
        placed(3, at(40, 64, 0));
        final Map<RailPos, RailLeg> byFrom = new LinkedHashMap<>();
        networks.legs().forEach(leg -> byFrom.put(leg.from(), leg));
        final RailLeg west = byFrom.get(at(0, 64, 0));

        track.rail(29, 64, 0, RailShape.SOUTH_WEST)
                .northSouth(29, 64, 1, 2)
                .rail(29, 64, 3, RailShape.NORTH_EAST)
                .eastWest(30, 30, 64, 3)
                .rail(31, 64, 3, RailShape.NORTH_WEST)
                .northSouth(31, 64, 1, 2)
                .rail(31, 64, 0, RailShape.SOUTH_EAST)
                .remove(30, 64, 0);
        final WorldNetworks.Change detour = changed(at(30, 64, 0), at(30, 64, 3));

        assertEquals(1, detour.removed().size());
        assertEquals(1, detour.added().size());
        assertEquals(26, detour.added().getFirst().edge().length());
        assertTrue(networks.legs().contains(west), "the edge away from the change is the same leg");
        assertEquals(
                46,
                Route.between(graphAt(at(0, 64, 0)), at(0, 64, 0), at(40, 64, 0))
                        .orElseThrow()
                        .length());
        sameAsAFreshTrace();
    }

    @Test
    void aChangeReadsTheTrackNearItOnly() {
        track.eastWest(0, 2_000, 64, 0);
        placed(1, at(0, 64, 0));
        placed(2, at(1_000, 64, 0));
        placed(3, at(2_000, 64, 0));
        track.readSinceLast();

        track.remove(1_500, 64, 0);
        changed(at(1_500, 64, 0));

        for (final int x : new int[] {1, 500, 999, 1_001, 1_250, 1_490, 1_510, 1_750, 1_999}) {
            assertFalse(track.wasRead(at(x, 64, 0)), "the rail at x " + x + " is far from the change");
        }
        final int read = track.readSinceLast();
        assertTrue(read < 100, "only the rails near the change and their neighbours are read, not " + read);
        assertEquals(2, networks.networks().size());
        sameAsAFreshTrace();
    }

    @Test
    void aChangeOnOneNetworkLeavesTheOtherAsItIs() {
        track.eastWest(0, 10, 64, 0).eastWest(0, 10, 64, 50);
        placed(1, at(0, 64, 0));
        placed(2, at(10, 64, 0));
        placed(3, at(0, 64, 50));
        placed(4, at(10, 64, 50));
        final RailGraph north = graphAt(at(0, 64, 0));
        final RailGraph south = graphAt(at(0, 64, 50));

        track.remove(5, 64, 0);
        changed(at(5, 64, 0));

        assertFalse(north.current());
        assertTrue(south.current(), "a ride on the other network keeps its route");
        assertSame(south, graphAt(at(0, 64, 50)));
    }

    @Test
    void aChangeAwayFromAnyTrackNeedsNoReCheck() {
        track.eastWest(0, 4, 64, 0);
        station(1, at(0, 64, 0), null, 0);
        changed(at(0, 64, 0));

        assertNull(networks.recheck(List.of(at(40, 64, 40)), track, CLOCK));
        assertEquals(1, networks.stationsNear(at(1, 65, 1)).size());
        assertTrue(networks.stationsNear(at(2, 64, 0)).isEmpty());
    }

    @Test
    void aStationWhoseRailIsGoneIsLost() {
        track.eastWest(0, 4, 64, 0);
        station(1, at(0, 64, 0), null, 0);
        station(2, at(4, 64, 0), null, 0);
        changed(at(0, 64, 0));

        track.remove(4, 64, 0);
        final WorldNetworks.Change change = changed(at(4, 64, 0));

        assertEquals(
                List.of(new UUID(0, 2)),
                change.lost().stream().map(StationRow::id).toList());
        assertTrue(networks.station(at(4, 64, 0)).isEmpty());
        assertTrue(networks.station(new UUID(0, 2)).isEmpty());
        sameAsAFreshTrace();
    }

    @Test
    void manyChangesInARowLeaveTheSameNetworksAsAFreshTrace() {
        track.eastWest(0, 30, 64, 0).northSouth(15, 64, 1, 10).eastWest(16, 30, 64, 10);
        placed(1, at(0, 64, 0));
        placed(2, at(30, 64, 0));
        placed(3, at(30, 64, 10));
        track.rail(15, 64, 10, RailShape.NORTH_EAST);
        changed(at(15, 64, 10));
        sameAsAFreshTrace();

        track.remove(7, 64, 0);
        changed(at(7, 64, 0));
        sameAsAFreshTrace();

        track.rail(7, 64, 0, RailShape.EAST_WEST);
        changed(at(7, 64, 0));
        sameAsAFreshTrace();

        track.remove(15, 64, 5).remove(16, 64, 10);
        changed(at(15, 64, 5), at(16, 64, 10));
        sameAsAFreshTrace();

        placed(4, at(15, 64, 3));
        sameAsAFreshTrace();

        networks.removeStation(at(30, 64, 0));
        changed(at(30, 64, 0));
        sameAsAFreshTrace();
    }

    @Test
    void theStoredGraphRebuildsTheSameNetworks() {
        track.eastWest(-3, 3, 64, 0).northSouth(0, 64, 1, 3).eastWest(20, 25, 64, 0);
        station(1, at(-3, 64, 0), null, 0);
        station(2, at(3, 64, 0), null, 0);
        station(3, at(0, 64, 3), null, 0);
        station(4, at(20, 64, 0), null, 0);
        station(5, at(25, 64, 0), null, 0);
        changed(at(-3, 64, 0), at(20, 64, 0));

        final WorldNetworks restored =
                WorldNetworks.restored(List.copyOf(networks.stations()), Set.of(at(0, 64, 0)), stored());

        assertEquals(2, restored.networks().size());
        final RailGraph junction =
                restored.networkOf(at(0, 64, 3)).orElseThrow().graph();
        assertEquals(Set.of(at(0, 64, 0)), junction.junctions());
        assertEquals(3, junction.stations().size());
        assertTrue(Route.between(junction, at(-3, 64, 0), at(0, 64, 3)).isPresent());
        assertEquals(
                restored.networkOf(at(1, 64, 0)).orElseThrow().nodes(),
                restored.networkOf(at(0, 64, 0)).orElseThrow().nodes(),
                "a rail of an edge finds its network through the index");
    }

    private Map<UUID, RailEdge> stored() {
        final Map<UUID, RailEdge> edges = new LinkedHashMap<>();
        for (final RailLeg leg : networks.legs()) {
            final @Nullable RailEdge edge = leg.edge();
            if (edge != null) {
                edges.put(leg.id(), edge);
            }
        }
        return edges;
    }

    @Test
    void aRestoredNetworkTracedAgainFindsItsSpurAndKeepsItsEdges() {
        track.eastWest(0, 10, 64, 0).northSouth(5, 64, 1, 4);
        station(1, at(0, 64, 0), null, 0);
        station(2, at(10, 64, 0), null, 0);
        changed(at(0, 64, 0));
        final RailGraph walked = networks.networks().iterator().next().graph();
        final Map<UUID, RailEdge> edges = stored();
        networks = WorldNetworks.restored(List.copyOf(networks.stations()), walked.junctions(), edges);
        assertTrue(networks.networkOf(at(5, 64, 4)).isEmpty(), "the store keeps no spur");

        final WorldNetworks.Change change = ran(networks.retrace(track, CLOCK));

        assertTrue(change.empty(), "nothing changed while the server was down");
        assertEquals(1, networks.networks().size());
        assertTrue(networks.networkOf(at(5, 64, 4)).isPresent(), "a rail placed at the spur's end must re-check it");
        assertEquals(edges.keySet(), stored().keySet());
        sameAsAFreshTrace();
    }

    @Test
    void aRestoredNetworkWhoseTrackChangedWhileTheServerWasDownIsTracedAnew() {
        track.eastWest(0, 20, 64, 0);
        placed(1, at(0, 64, 0));
        placed(2, at(10, 64, 0));
        placed(3, at(20, 64, 0));
        networks = WorldNetworks.restored(List.copyOf(networks.stations()), Set.of(), stored());
        track.remove(15, 64, 0);

        final WorldNetworks.Change change = ran(networks.retrace(track, CLOCK));

        assertEquals(1, change.removed().size());
        assertEquals(2, networks.networks().size());
        sameAsAFreshTrace();
    }

    @Test
    void theRailsOfGoneLegsAreTidiedAwayALittleAtATime() {
        track.eastWest(0, 100, 64, 0);
        placed(1, at(0, 64, 0));
        placed(2, at(100, 64, 0));
        track.remove(50, 64, 0);
        changed(at(50, 64, 0));
        final int before = networks.indexed();
        final int held =
                networks.legs().stream().mapToInt(leg -> leg.rails().size()).sum();
        assertTrue(before > held, "the broken rail is still indexed");

        networks.tidy(1);
        assertTrue(networks.indexed() >= held, "a rail of a held leg stays indexed");
        for (int tick = 0; tick < 300; tick++) {
            networks.tidy(1);
        }

        assertEquals(held, networks.indexed());
    }

    @Test
    void aRailWhosePlaceWaitsToBeMovedStaysOnItsLegWhileTheNextReCheckRuns() {
        track.eastWest(0, 100, 64, 0);
        placed(1, at(0, 64, 0));
        placed(2, at(100, 64, 0));
        track.remove(50, 64, 0);
        changed(at(50, 64, 0));
        track.rail(50, 64, 0, RailShape.EAST_WEST);
        changed(at(50, 64, 0));

        track.remove(80, 64, 0);
        final @Nullable RailRecheck job = networks.recheck(List.of(at(80, 64, 0)), track, CLOCK);
        assertNotNull(job);
        for (int steps = 0; !job.step(1, Long.MAX_VALUE); steps++) {
            assertTrue(steps < 100_000, "the re-check did not end");
            assertTrue(networks.networkOf(at(20, 64, 0)).isPresent(), "a rail of the held line, at step " + steps);
        }
        job.change();
        for (int tick = 0; tick < 300; tick++) {
            networks.tidy(1);
        }

        assertTrue(networks.networkOf(at(20, 64, 0)).isPresent());
        sameAsAFreshTrace();
    }
}
