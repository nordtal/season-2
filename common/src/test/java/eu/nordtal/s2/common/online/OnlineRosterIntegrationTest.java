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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Exercises {@link OnlineRoster} against a real PostgreSQL running the real migration, the same way
 * {@link OnlineDirectoryIntegrationTest} does for the counts - and for the same reason: the upsert
 * and the prune are one transaction's worth of SQL, and no in-memory fake can say anything about
 * whether they actually replace a roster.
 */
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

    // ---------------------------------------------------------------- writing and reading back

    @Test
    @DisplayName("a roster nobody has written is empty, not a list of nulls")
    void anUnwrittenRosterIsEmpty() {
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
    @DisplayName("a player the proxy has and no backend does yet keeps a NULL subject, not a guess")
    void aPlayerBetweenServersHasNoSubject() {
        roster.replace(List.of(new OnlineRoster.Presence(ADA, "Ada", null)));

        final OnlinePlayer player = roster.current().getFirst();
        assertEquals(Optional.empty(), player.on(), "no server is not the same as some server");
        assertTrue(player.name().equals("Ada"), "they are still online, and still have a name");
    }

    @Test
    @DisplayName("the name that came with the second write wins - a name is an observation")
    void aRenamedPlayerKeepsOneRow() {
        roster.replace(List.of(new OnlineRoster.Presence(ADA, "Ada", "limbo")));
        roster.replace(List.of(new OnlineRoster.Presence(ADA, "AdaRenamed", "smp")));

        assertEquals(
                1,
                count("SELECT count(*) FROM online_player"),
                "one player is one row, whatever they are called this week");
        assertEquals("AdaRenamed", roster.current().getFirst().name());
        assertEquals(Optional.of("smp"), roster.current().getFirst().on());
    }

    // ---------------------------------------------------------------- the set shrinks, too

    @Test
    @DisplayName("a player who logs off is DELETED, not left with an ageing timestamp")
    void aPlayerWhoLeavesIsDeleted() {
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
    @DisplayName("an empty write empties the table - nobody online is a statement, not a no-op")
    void anEmptyWriteClearsEverybody() {
        roster.replace(List.of(new OnlineRoster.Presence(ADA, "Ada", "smp")));

        roster.replace(List.of());

        assertTrue(roster.current().isEmpty());
        assertEquals(0, count("SELECT count(*) FROM online_player"));
    }

    @Test
    @DisplayName("`updated` moves forward for everyone still there")
    void updatedAdvancesForTheOnesWhoStayed() throws InterruptedException {
        roster.replace(List.of(new OnlineRoster.Presence(ADA, "Ada", "smp")));
        final Instant first = roster.current().getFirst().updated();

        Thread.sleep(5);
        roster.replace(List.of(new OnlineRoster.Presence(ADA, "Ada", "smp")));

        assertFalse(
                roster.current().getFirst().updated().isBefore(first),
                "a player still connected must not carry a timestamp that ages towards stale");
    }

    @Test
    @DisplayName("one write is one moment - every row of it carries the same instant")
    void oneWriteIsOneInstant() {
        roster.replace(
                List.of(new OnlineRoster.Presence(ADA, "Ada", "smp"), new OnlineRoster.Presence(BEN, "Ben", null)));

        final List<Instant> stamps =
                roster.current().stream().map(OnlinePlayer::updated).toList();
        assertEquals(
                1,
                stamps.stream().distinct().count(),
                "two rows of one write must not be able to disagree about when it happened");
    }

    // ---------------------------------------------------------------- refusals

    @Test
    @DisplayName("the same player twice in one write is refused before anything is written")
    void aDuplicateIsRefused() {
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

    // ---------------------------------------------------------------- plumbing

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
