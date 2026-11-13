package eu.nordtal.season.database.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParser;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.pack.PackBuildStore.PackBuild;
import eu.nordtal.season.database.pack.PackBuildStore.State;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.junit.jupiter.api.Test;

/** The record of the pack builds against PostgreSQL and the real migrations. */
class PackBuildStoreIntegrationTest {

    private static final Instant AT = Instant.parse("2026-11-13T10:00:00Z");
    private static final String PRIZES = "[{\"item\": \"minecraft:cooked_beef\", \"amount\": 32}]";
    private static final Map<String, String> LABEL = Map.of("en", "Spin", "de", "Drehen");
    private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void aGoodBuildBecomesTheActivePackAndSupersedesTheOneBefore() {
        final PackBuildStore store = PackBuildStore.using(TestDatabase.fresh().dataSource());
        final long first = store.start(AT, PRIZES, LABEL);
        store.succeed(first, AT.plusSeconds(4), SHA1, 1234);
        final long second = store.start(AT.plusSeconds(60), PRIZES, LABEL);
        store.succeed(second, AT.plusSeconds(62), "f".repeat(40), 99);

        final List<PackBuild> builds = store.recent(5);
        assertEquals(List.of(second, first), builds.stream().map(PackBuild::id).toList());
        assertEquals(
                List.of(State.ACTIVE, State.SUPERSEDED),
                builds.stream().map(PackBuild::state).toList());
        final PackBuild active = store.active().orElseThrow();
        assertEquals("f".repeat(40), active.sha1());
        assertEquals(99L, active.sizeBytes());
        assertEquals(Duration.ofSeconds(2), active.duration());
        assertEquals(LABEL, active.label());
        // jsonb spaces the text and orders the keys anew, so the prizes come back equal as JSON, not as text.
        assertEquals(JsonParser.parseString(PRIZES), JsonParser.parseString(active.prizes()));
    }

    @Test
    void aFailedBuildLeavesTheActivePackAsItWas() {
        final PackBuildStore store = PackBuildStore.using(TestDatabase.fresh().dataSource());
        final long good = store.start(AT, PRIZES, LABEL);
        store.succeed(good, AT.plusSeconds(4), SHA1, 1234);
        final long bad = store.start(AT.plusSeconds(60), PRIZES, LABEL);
        store.fail(bad, AT.plusSeconds(61), "the child process ended with 1");

        assertEquals(good, store.active().orElseThrow().id());
        final PackBuild failed = store.recent(1).getFirst();
        assertEquals(State.FAILED, failed.state());
        assertEquals("the child process ended with 1", failed.error());
        assertNull(failed.sha1());
    }

    @Test
    void aBuildTheAgentLeftRunningEndsAsFailedAtTheNextStart() {
        final PackBuildStore store = PackBuildStore.using(TestDatabase.fresh().dataSource());
        store.start(AT, PRIZES, LABEL);
        store.abandon(AT.plusSeconds(30), "steward-agent stopped during the build");

        final PackBuild build = store.recent(1).getFirst();
        assertEquals(State.FAILED, build.state());
        assertEquals(Duration.ofSeconds(30), build.duration());
    }

    @Test
    void stewardReadsTheBuildsAndWritesNone() {
        final TestDatabase database = TestDatabase.fresh();
        PackBuildStore.using(database.dataSource()).start(AT, PRIZES, LABEL);

        final PackBuildStore steward = PackBuildStore.using(database.dataSourceAs(DatabaseRole.STEWARD));
        assertEquals(1, steward.recent(5).size());
        final Jdbi asSteward = Jdbi.create(database.dataSourceAs(DatabaseRole.STEWARD));
        assertThrows(
                UnableToExecuteStatementException.class,
                () -> asSteward.useHandle(handle -> handle.execute("DELETE FROM pack_build")));
    }

    @Test
    void aSecondActiveBuildIsRefused() {
        final TestDatabase database = TestDatabase.fresh();
        final PackBuildStore store = PackBuildStore.using(database.dataSource());
        store.succeed(store.start(AT, PRIZES, LABEL), AT.plusSeconds(1), SHA1, 1);
        final long other = store.start(AT, PRIZES, LABEL);
        final Jdbi owner = Jdbi.create(database.dataSource());
        assertThrows(
                UnableToExecuteStatementException.class,
                () -> owner.useHandle(handle -> handle.execute(
                        "UPDATE pack_build SET state = 'ACTIVE', finished = now(), sha1 = ? WHERE id = ?",
                        SHA1,
                        other)));
    }
}
