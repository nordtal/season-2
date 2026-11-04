package eu.nordtal.season.smp.rail;

import static eu.nordtal.season.smp.rail.Track.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.TestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Stores rail networks in a real PostgreSQL with the real migrations and reads them back. Skips without Docker. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RailStoreIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    private static final Executor NOW_HERE = Runnable::run;
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static DataSource dataSource;

    private final Track track = new Track();
    private WorldNetworks networks;
    private RailStore store;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void emptyStore() {
        execute("TRUNCATE TABLE smp_rail_station, smp_rail_junction, smp_rail_edge CASCADE");
        networks = new WorldNetworks();
        store = new RailStore(Jdbis.over(dataSource), NOW_HERE, Logger.getAnonymousLogger());
    }

    private void station(final int id, final RailPos pos, final String name, final int minute) {
        final StationRow row = new StationRow(
                new UUID(0, id),
                "nordtal",
                pos.x(),
                pos.y(),
                pos.z(),
                track.rails().getOrDefault(pos, RailShape.EAST_WEST).name(),
                new UUID(1, id),
                null,
                null);
        networks.addStation(row);
        store.placed(row);
        final StationRow named = row.renamed(name, NOW.plusSeconds(60L * minute));
        networks.addStation(named);
        store.named(named);
    }

    private static void execute(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (final SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void changed(final RailPos... positions) {
        final @Nullable RailRecheck job = networks.recheck(List.of(positions), track, CLOCK);
        if (job == null) {
            return;
        }
        while (!job.step(1_000, Long.MAX_VALUE)) {
            // Every position is readable.
        }
        store.changed("nordtal", job.change());
    }

    private RailStore.Loaded load() {
        final List<RailStore.Loaded> loaded = new ArrayList<>();
        store.load(loaded::add, NOW_HERE);
        return loaded.getFirst();
    }

    private WorldNetworks loaded() {
        final RailStore.Stored stored = load().worlds().get("nordtal");
        return WorldNetworks.restored(stored.stations(), stored.junctions(), stored.edges());
    }

    @Test
    void aMergedNetworkComesBackWithItsJunctionEdgesAndSettledNames() {
        track.eastWest(0, 4, 64, 0).eastWest(6, 10, 64, 0).northSouth(5, 64, 1, 6);
        station(1, at(0, 64, 0), "Spawn", 0);
        station(2, at(10, 64, 0), "Spawn", 5);
        station(3, at(5, 64, 6), "Mine", 9);
        changed(at(0, 64, 0));
        changed(at(10, 64, 0));
        changed(at(5, 64, 6));

        track.rail(5, 64, 0, RailShape.EAST_WEST);
        changed(at(5, 64, 0));

        final WorldNetworks restored = loaded();
        assertEquals(1, restored.networks().size());
        final RailGraph graph = restored.networks().iterator().next().graph();
        assertEquals(Set.of(at(5, 64, 0)), graph.junctions());
        assertEquals(3, graph.edges().size());
        assertEquals("Spawn", restored.station(at(0, 64, 0)).orElseThrow().name());
        assertEquals("Spawn 2", restored.station(at(10, 64, 0)).orElseThrow().name());
        assertEquals(
                11,
                Route.between(graph, at(0, 64, 0), at(5, 64, 6)).orElseThrow().length());
    }

    @Test
    void aSplitNetworkComesBackInTwoParts() {
        track.eastWest(0, 10, 64, 0);
        station(1, at(0, 64, 0), "West", 0);
        station(2, at(10, 64, 0), "East", 1);
        changed(at(0, 64, 0));

        track.remove(5, 64, 0);
        changed(at(5, 64, 0));

        final WorldNetworks restored = loaded();
        assertEquals(2, restored.networks().size());
        assertFalse(restored.networkOf(at(0, 64, 0))
                .orElseThrow()
                .graph()
                .stations()
                .contains(at(10, 64, 0)));
    }

    @Test
    void aFavouriteOutlivesAMergeAndGoesWithItsStation() {
        track.eastWest(0, 4, 64, 0).eastWest(6, 10, 64, 0);
        station(1, at(0, 64, 0), "West", 0);
        station(2, at(10, 64, 0), "East", 1);
        changed(at(0, 64, 0));
        changed(at(10, 64, 0));
        final UUID player = new UUID(7, 7);
        store.favoured(player, new UUID(0, 2), true);

        track.rail(5, 64, 0, RailShape.EAST_WEST);
        changed(at(5, 64, 0));
        assertEquals(List.of(new FavouriteRow(player, new UUID(0, 2))), load().favourites());

        networks.removeStation(at(10, 64, 0));
        store.removed(new UUID(0, 2));
        assertEquals(List.of(), load().favourites());
    }

    @Test
    void aRenameByAMergeWaitsForItsPlacerUntilSeen() {
        track.eastWest(0, 4, 64, 0).eastWest(6, 10, 64, 0);
        station(1, at(0, 64, 0), "Spawn", 0);
        station(2, at(10, 64, 0), "Spawn", 5);
        changed(at(0, 64, 0));
        changed(at(10, 64, 0));

        track.rail(5, 64, 0, RailShape.EAST_WEST);
        changed(at(5, 64, 0));
        assertEquals(Set.of(new UUID(0, 2)), load().unseen());

        store.seen(new UUID(0, 2));
        assertEquals(Set.of(), load().unseen());
    }

    @Test
    void aChangeRewritesOnlyTheEdgesItTouches() {
        track.eastWest(0, 40, 64, 0);
        station(1, at(0, 64, 0), "West", 0);
        station(2, at(20, 64, 0), "Middle", 1);
        station(3, at(40, 64, 0), "East", 2);
        changed(at(0, 64, 0));
        changed(at(20, 64, 0));
        changed(at(40, 64, 0));
        final Map<UUID, RailEdge> before = load().worlds().get("nordtal").edges();
        final UUID west = before.entrySet().stream()
                .filter(entry -> entry.getValue().from().equals(at(0, 64, 0)))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();

        track.remove(30, 64, 0);
        changed(at(30, 64, 0));

        final Map<UUID, RailEdge> after = load().worlds().get("nordtal").edges();
        assertTrue(after.containsKey(west), "the edge away from the change keeps its row");
        assertEquals(1, after.size(), "the broken edge between the middle and the east is gone");
        final WorldNetworks restored = loaded();
        assertEquals(2, restored.networks().size());
    }

    @Test
    void aRestoredWorldTracedAgainKeepsItsEdges() {
        track.eastWest(-3, 3, 64, 0).northSouth(0, 64, 1, 3);
        station(1, at(-3, 64, 0), "West", 0);
        station(2, at(3, 64, 0), "East", 1);
        station(3, at(0, 64, 3), "South", 2);
        changed(at(-3, 64, 0));
        changed(at(3, 64, 0));
        changed(at(0, 64, 3));
        final Set<UUID> stored = load().worlds().get("nordtal").edges().keySet();
        networks = loaded();

        final RailRecheck whole = networks.retrace(track, CLOCK);
        while (!whole.step(1_000, Long.MAX_VALUE)) {
            // Every position is readable.
        }
        store.changed("nordtal", whole.change());

        assertTrue(whole.change().empty(), "nothing changed while the server was down");
        assertEquals(stored, load().worlds().get("nordtal").edges().keySet());
        assertEquals(
                Set.of(at(0, 64, 0)),
                networks.networks().iterator().next().graph().junctions());
    }

    @Test
    void aStationPlacedWhereTheStoreStillHoldsOneReplacesItWithItsFavourites() {
        station(1, at(0, 64, 0), "Spawn", 0);
        store.favoured(new UUID(7, 7), new UUID(0, 1), true);

        store.placed(new StationRow(new UUID(0, 9), "nordtal", 0, 64, 0, "EAST_WEST", new UUID(1, 9), null, null));

        final RailStore.Loaded loaded = load();
        assertEquals(
                List.of(new UUID(0, 9)),
                loaded.worlds().get("nordtal").stations().stream()
                        .map(StationRow::id)
                        .toList());
        assertEquals(List.of(), loaded.favourites());
    }

    @Test
    void aHiddenStationStaysHiddenUntilShownAgain() {
        station(1, at(0, 64, 0), "West", 0);
        station(2, at(10, 64, 0), "East", 1);

        store.hidden(new UUID(0, 2), true);
        assertEquals(Set.of(new UUID(0, 2)), load().hidden());

        store.hidden(new UUID(0, 2), false);
        assertEquals(Set.of(), load().hidden());
    }

    @Test
    void theSpawnStationIsStoredWithoutAPlacer() {
        store.placed(new StationRow(new UUID(0, 5), "nordtal", 0, 64, 0, "NORTH_SOUTH", null, null, null));

        final StationRow spawn = load().worlds().get("nordtal").stations().getFirst();
        assertTrue(spawn.spawn());
        assertEquals("NORTH_SOUTH", spawn.axis());
    }
}
