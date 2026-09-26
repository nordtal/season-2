package eu.nordtal.s2.common.online;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.s2.common.access.AccessSchema;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/** Exercises {@link OnlineRoster}'s upsert and prune against a real PostgreSQL. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnlineRosterIntegrationTest {

    private static final UUID ADA = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID BEN = UUID.fromString("00000000-0000-4000-8000-000000000002");

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private OnlineRoster roster;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed roster tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("access")
                .withUsername("access")
                .withPassword("access");
        postgres.start();

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        AccessSchema.migrate(dataSource);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
        dataSource = null;
    }

    @BeforeEach
    void freshTable() {
        execute("TRUNCATE TABLE online_player");
        roster = OnlineRoster.using(dataSource);
    }

    @Test
    void aRosterNobodyHasWrittenIsEmptyNotAListOfNulls() {
        assertTrue(roster.current().isEmpty());
    }

    @Test
    void aWrittenPlayerComesBackWhole() {
        roster.replace(List.of(new OnlineRoster.Presence(ADA, "Ada", "smp")));

        final List<OnlinePlayer> current = roster.current();
        assertEquals(1, current.size());
        assertEquals(ADA, current.getFirst().uuid());
        assertEquals("Ada", current.getFirst().name());
        assertEquals(Optional.of("smp"), current.getFirst().on());
    }

    @Test
    void aPlayerTheProxyHasAndNoBackendDoesYetKeepsANullSubjectNotAGuess() {
        roster.replace(List.of(new OnlineRoster.Presence(ADA, "Ada", null)));

        final OnlinePlayer player = roster.current().getFirst();
        assertEquals(Optional.empty(), player.on(), "no server is not the same as some server");
        assertTrue(player.name().equals("Ada"), "they are still online, and still have a name");
    }

    @Test
    void theNameThatCameWithTheSecondWriteWinsANameIsAnObservation() {
        roster.replace(List.of(new OnlineRoster.Presence(ADA, "Ada", "limbo")));
        roster.replace(List.of(new OnlineRoster.Presence(ADA, "AdaRenamed", "smp")));

        assertEquals(
                1,
                count("SELECT count(*) FROM online_player"),
                "one player is one row, whatever they are called this week");
        assertEquals("AdaRenamed", roster.current().getFirst().name());
        assertEquals(Optional.of("smp"), roster.current().getFirst().on());
    }

    @Test
    void aPlayerWhoLogsOffIsDeletedNotLeftWithAnAgeingTimestamp() {
        roster.replace(
                List.of(new OnlineRoster.Presence(ADA, "Ada", "smp"), new OnlineRoster.Presence(BEN, "Ben", "smp")));
        assertEquals(2, roster.current().size());

        roster.replace(List.of(new OnlineRoster.Presence(ADA, "Ada", "smp")));

        assertEquals(
                List.of(ADA),
                roster.current().stream().map(OnlinePlayer::uuid).toList(),
                "Ben logged off, so Ben is gone - a reader must not have to age him out");
    }

    @Test
    void anEmptyWriteEmptiesTheTableNobodyOnlineIsAStatementNotANoOp() {
        roster.replace(List.of(new OnlineRoster.Presence(ADA, "Ada", "smp")));

        roster.replace(List.of());

        assertTrue(roster.current().isEmpty());
        assertEquals(0, count("SELECT count(*) FROM online_player"));
    }

    @Test
    void updatedMovesForwardForEveryoneStillThere() throws InterruptedException {
        roster.replace(List.of(new OnlineRoster.Presence(ADA, "Ada", "smp")));
        final Instant first = roster.current().getFirst().updated();

        Thread.sleep(5);
        roster.replace(List.of(new OnlineRoster.Presence(ADA, "Ada", "smp")));

        assertFalse(
                roster.current().getFirst().updated().isBefore(first),
                "a player still connected must not carry a timestamp that ages towards stale");
    }

    @Test
    void oneWriteIsOneMomentEveryRowOfItCarriesTheSameInstant() {
        roster.replace(
                List.of(new OnlineRoster.Presence(ADA, "Ada", "smp"), new OnlineRoster.Presence(BEN, "Ben", null)));

        final List<Instant> stamps =
                roster.current().stream().map(OnlinePlayer::updated).toList();
        assertEquals(
                1,
                stamps.stream().distinct().count(),
                "two rows of one write must not be able to disagree about when it happened");
    }

    @Test
    void theSamePlayerTwiceInOneWriteIsRefusedBeforeAnythingIsWritten() {
        assertThrows(
                IllegalArgumentException.class,
                () -> roster.replace(List.of(
                        new OnlineRoster.Presence(ADA, "Ada", "smp"), new OnlineRoster.Presence(ADA, "Ada", "limbo"))));

        assertTrue(roster.current().isEmpty(), "the refused write must not have left half a roster");
    }

    @Test
    void aBlankNameIsRefusedByThePresenceItself() {
        assertThrows(IllegalArgumentException.class, () -> new OnlineRoster.Presence(ADA, " ", "smp"));
    }

    private long count(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void execute(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
