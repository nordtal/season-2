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

class WorldNetworksTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    private final Track track = new Track();
    private final WorldNetworks networks = new WorldNetworks();

    private void station(final int id, final RailPos pos, final @Nullable String name, final int minute) {
        networks.addStation(new StationRow(
                new UUID(0, id),
                "nordtal",
                pos.x(),
                pos.y(),
                pos.z(),
                "EAST_WEST",
                new UUID(1, id),
                name,
                name == null ? null : NOW.minusSeconds(3_600L - 60L * minute)));
    }

    private WorldNetworks.Replacement changed(final RailPos... positions) {
        final WorldNetworks.Walk walk = networks.walkFor(List.of(positions));
        final RailWalk rails = new RailWalk(track, walk.starts(), 10_000);
        while (!rails.step(1_000)) {
            // Every position is readable.
        }
        return networks.replace(walk, rails.found(), rails.missing(), NOW);
    }

    private @Nullable String nameAt(final RailPos pos) {
        return networks.station(pos).orElseThrow().name();
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
        final WorldNetworks.Replacement merged = changed(at(5, 64, 0));

        assertEquals(1, networks.networks().size());
        assertEquals(2, merged.removed().size());
        assertEquals("Spawn", nameAt(at(0, 64, 0)));
        assertEquals("Spawn 2", nameAt(at(10, 64, 0)));
        assertEquals(
                List.of(new UUID(0, 2)),
                merged.renamed().stream().map(StationRow::id).toList());
    }

    @Test
    void aBrokenRailSplitsANetworkInTwo() {
        track.eastWest(0, 10, 64, 0);
        station(1, at(0, 64, 0), "West", 0);
        station(2, at(10, 64, 0), "East", 1);
        changed(at(0, 64, 0));
        assertEquals(1, networks.networks().size());

        track.remove(5, 64, 0);
        changed(at(5, 64, 0));

        assertEquals(2, networks.networks().size());
        assertFalse(networks.networkOf(at(0, 64, 0)).orElseThrow().rails().contains(at(10, 64, 0)));
    }

    @Test
    void aRailNextToNoNetworkNeedsNoWalk() {
        track.eastWest(0, 4, 64, 0);
        station(1, at(0, 64, 0), null, 0);
        changed(at(0, 64, 0));

        assertTrue(networks.walkFor(List.of(at(40, 64, 40))).starts().isEmpty());
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
        final WorldNetworks.Replacement replacement = changed(at(4, 64, 0));

        assertEquals(
                List.of(new UUID(0, 2)),
                replacement.lost().stream().map(StationRow::id).toList());
        assertTrue(networks.station(at(4, 64, 0)).isEmpty());
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
        final Set<WorldNetworks.Network> walked = networks.networks();

        final WorldNetworks restored = new WorldNetworks();
        final List<RailEdge> edges = walked.stream()
                .flatMap(network -> network.graph().edges().stream())
                .toList();
        restored.restore(List.copyOf(networks.stations()), Set.of(at(0, 64, 0)), edges);

        assertEquals(2, restored.networks().size());
        final RailGraph junction =
                restored.networkOf(at(0, 64, 3)).orElseThrow().graph();
        assertEquals(Set.of(at(0, 64, 0)), junction.junctions());
        assertEquals(3, junction.stations().size());
        assertTrue(Route.between(junction, at(-3, 64, 0), at(0, 64, 3)).isPresent());
    }

    @Test
    void aRestoredNetworkWalkedFromItsStationsTracksItsSpurAgain() {
        track.eastWest(0, 10, 64, 0).northSouth(5, 64, 1, 4);
        station(1, at(0, 64, 0), null, 0);
        station(2, at(10, 64, 0), null, 0);
        changed(at(0, 64, 0));
        final RailGraph walked = networks.networks().iterator().next().graph();
        final WorldNetworks restored = new WorldNetworks();
        restored.restore(List.copyOf(networks.stations()), walked.junctions(), walked.edges());
        assertTrue(restored.networkOf(at(5, 64, 4)).isEmpty(), "the store keeps no spur");

        final WorldNetworks.Walk walk = restored.walkFor(List.of(at(0, 64, 0), at(10, 64, 0)));
        final RailWalk rails = new RailWalk(track, walk.starts(), 10_000);
        while (!rails.step(1_000)) {
            // Every position is readable.
        }
        restored.replace(walk, rails.found(), rails.missing(), NOW);

        assertEquals(1, restored.networks().size());
        assertTrue(restored.networkOf(at(5, 64, 4)).isPresent(), "a rail placed at the spur's end must start a walk");
    }
}
