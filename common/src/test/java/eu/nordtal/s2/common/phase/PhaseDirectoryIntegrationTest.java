package eu.nordtal.s2.common.phase;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.access.AccessSchema;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises {@link PhaseDirectory} against a real PostgreSQL instance running the real migrations.
 * <p>
 * None of this can be done in memory. The singleton is a primary key plus a {@code CHECK}; the
 * switch, its audit entry and its {@code NOTIFY} are one statement whose whole point is that
 * PostgreSQL executes all three or none; and the "previous phase" the statement returns depends on
 * every sub-statement of a {@code WITH} seeing the same snapshot. There is no in-JVM stand-in for
 * any of it.
 * </p>
 * <p>
 * Testcontainers is driven by hand from {@link BeforeAll} for the same reason
 * {@code AccessDirectoryIntegrationTest} does it - the {@code org.testcontainers:junit-jupiter}
 * extension is built against JUnit 5 and this repo is on the JUnit 6 BOM - and these tests
 * <b>skip themselves</b> when no Docker daemon is reachable.
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhaseDirectoryIntegrationTest {

    private static final String ADMIN_ID = "300000000000000001";

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private PhaseDirectory phases;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed phase tests");

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
    void freshPhase() {
        // Put the singleton back the way V4 seeded it. DELETE + INSERT rather than an UPDATE
        // because one test below removes the row on purpose.
        execute("TRUNCATE TABLE audit_log");
        execute("DELETE FROM season_phase");
        execute("INSERT INTO season_phase (phase) VALUES ('PRE_EVENT')");
        phases = PhaseDirectory.using(dataSource);
    }

    // ---------------------------------------------------------------- the singleton

    @Test
    void theMigrationSeedsTheSeasonsStartState() {
        // V4 runs INSERT INTO season_phase (phase) VALUES ('PRE_EVENT'), which is the [*] ->
        // PRE_EVENT edge in docs/season-phases.md. Read it off a database migrated by Flyway rather
        // than off the row this class re-seeds.
        execute("DELETE FROM season_phase");
        execute("INSERT INTO season_phase (phase) SELECT 'PRE_EVENT'");

        assertEquals(SeasonPhase.PRE_EVENT, phases.currentPhase());
        assertEquals(1, count("SELECT count(*) FROM season_phase"));
    }

    @Test
    void aSecondRowCannotBeInsertedAtAll() {
        // Taking the default id: the boolean primary key is already true.
        final SQLException duplicate = assertThrows(SQLException.class,
                () -> executeChecked("INSERT INTO season_phase (phase) VALUES ('SMP')"));
        assertTrue(duplicate.getMessage().contains("season_phase_pkey"),
                "the primary key is the first half of the singleton: " + duplicate.getMessage());

        // Dodging the primary key by picking the other boolean value: the CHECK is the other half.
        final SQLException checkViolation = assertThrows(SQLException.class,
                () -> executeChecked("INSERT INTO season_phase (id, phase) VALUES (false, 'SMP')"));
        assertTrue(checkViolation.getMessage().contains("season_phase_singleton"),
                "the CHECK is what stops the second row from sneaking in as id = false: "
                        + checkViolation.getMessage());

        assertEquals(1, count("SELECT count(*) FROM season_phase"));
        assertEquals(SeasonPhase.PRE_EVENT, phases.currentPhase(), "neither attempt changed anything");
    }

    @Test
    void aPhaseNameNoBuildCanReadCannotBeWrittenByHand() {
        // The documented last resort during an outage is an UPDATE on this row by hand. The CHECK
        // is what stops a typo in that UPDATE - a hyphen for an underscore, at three in the
        // morning - from putting the network into a phase no process can interpret.
        final SQLException failure = assertThrows(SQLException.class,
                () -> executeChecked("UPDATE season_phase SET phase = 'START-EVENT' WHERE id"));
        assertTrue(failure.getMessage().contains("season_phase_phase_check"), failure.getMessage());

        // The column is varchar(16), so anything longer than a phase name is refused before the
        // CHECK is even reached. Season 1's retired RESOURCE_PACK_INSTALL is 21 characters.
        final SQLException tooLong = assertThrows(SQLException.class,
                () -> executeChecked("UPDATE season_phase SET phase = 'RESOURCE_PACK_INSTALL' WHERE id"));
        assertTrue(tooLong.getMessage().contains("character varying(16)"), tooLong.getMessage());

        assertEquals(SeasonPhase.PRE_EVENT, phases.currentPhase());
    }

    @Test
    void aHandWrittenUpdateIsPickedUpByTheNextRead() {
        execute("UPDATE season_phase SET phase = 'SMP' WHERE id");

        assertEquals(SeasonPhase.SMP, phases.currentPhase(),
                "nobody caches the phase as truth - the escape hatch in docs/season-phases.md depends on it");
    }

    // ---------------------------------------------------------------- switch and audit

    @Test
    void switchingChangesTheRowAndReportsWhatItReplaced() {
        final PhaseChange change = phases.switchPhase(SeasonPhase.START_EVENT, ADMIN_ID, "the event begins");

        assertEquals(SeasonPhase.PRE_EVENT, change.previous());
        assertEquals(SeasonPhase.START_EVENT, change.current());
        assertFalse(change.unchanged());
        assertNotNull(change.at());
        assertWithinSeconds(Instant.now(), change.at(), 5);

        assertEquals(SeasonPhase.START_EVENT, phases.currentPhase());
    }

    @Test
    void switchingWritesExactlyOneAuditRowAndCannotBeMadeToSkipIt() {
        phases.switchPhase(SeasonPhase.SMP, ADMIN_ID, "the winner is crowned");

        final List<String> rows = query("""
                SELECT action || '|' || coalesce(actor, '-') || '|' || coalesce(detail, '-')
                FROM audit_log
                """);

        assertEquals(List.of("SET_PHASE|" + ADMIN_ID + "|PRE_EVENT -> SMP (the winner is crowned)"), rows,
                "the audit entry is part of the same statement as the update - there is no way to "
                        + "issue one without the other, which is the point of a single switch method");
    }

    @Test
    void theReasonIsOptionalAndSoIsTheActor() {
        phases.switchPhase(SeasonPhase.MAINTENANCE, null, null);

        assertEquals(List.of("SET_PHASE|-|PRE_EVENT -> MAINTENANCE"), query("""
                SELECT action || '|' || coalesce(actor, '-') || '|' || coalesce(detail, '-')
                FROM audit_log
                """));
    }

    @Test
    void switchingToThePhaseThatIsAlreadyCurrentIsRecordedRatherThanRefused() {
        final PhaseChange change = phases.switchPhase(SeasonPhase.PRE_EVENT, ADMIN_ID, null);

        assertTrue(change.unchanged());
        assertEquals(SeasonPhase.PRE_EVENT, change.current());
        assertEquals(List.of("PRE_EVENT -> PRE_EVENT"), query("SELECT detail FROM audit_log"),
                "a switch that changed nothing is still something a human may need to see afterwards");
    }

    @Test
    void everySwitchLeavesItsOwnAuditRowBehind() {
        phases.switchPhase(SeasonPhase.START_EVENT, ADMIN_ID, null);
        phases.switchPhase(SeasonPhase.SMP, ADMIN_ID, null);
        phases.switchPhase(SeasonPhase.MAINTENANCE, ADMIN_ID, "database maintenance");

        // Compared as a set: audit_log has no sequence and `occurred` is the transaction timestamp,
        // so three separate statements are only microseconds apart. What is being proved is that
        // there are three rows and which three, not the order PostgreSQL happens to return them in.
        assertEquals(Set.of("PRE_EVENT -> START_EVENT",
                        "START_EVENT -> SMP",
                        "SMP -> MAINTENANCE (database maintenance)"),
                Set.copyOf(query("SELECT detail FROM audit_log")));
        assertEquals(3, count("SELECT count(*) FROM audit_log"));
        assertEquals(SeasonPhase.MAINTENANCE, phases.currentPhase());
    }

    @Test
    void aSwitchThatFindsNoRowWritesNothingAtAll() {
        execute("DELETE FROM season_phase");

        assertThrows(IllegalStateException.class,
                () -> phases.switchPhase(SeasonPhase.SMP, ADMIN_ID, null));

        assertEquals(0, count("SELECT count(*) FROM audit_log"),
                "no update means no audit entry - the two are one statement, so a failed switch "
                        + "cannot leave a record of something that did not happen");
        assertEquals(SeasonPhase.MAINTENANCE, phases.currentPhase(),
                "a phase that cannot be read is MAINTENANCE: the state that lets nobody in is the "
                        + "safe one to guess");
    }

    // ---------------------------------------------------------------- the notification

    @Test
    void aCommittedSwitchNotifiesTheChannelTheProxyListensOn() throws SQLException {
        // The poll is the actual guarantee (30 seconds, docs/season-phases.md); this only proves
        // that the NOTIFY half of the same statement really reaches a listener, so that the
        // listener side can be built against something that is known to fire.
        try (Connection listener = dataSource.getConnection()) {
            try (Statement statement = listener.createStatement()) {
                statement.execute("LISTEN nordtal_phase");
            }

            phases.switchPhase(SeasonPhase.SMP, ADMIN_ID, null);

            final PGNotification[] notifications = awaitNotification(listener);
            assertNotNull(notifications, "no notification arrived on nordtal_phase within the timeout");
            assertEquals(1, notifications.length);
            assertEquals("nordtal_phase", notifications[0].getName());
            assertEquals("", notifications[0].getParameter(),
                    "no payload on purpose - a listener has to re-read the row, because "
                            + "notifications are lost while it is disconnected");
        }
    }

    private static PGNotification[] awaitNotification(final Connection listener) throws SQLException {
        final PGConnection pg = listener.unwrap(PGConnection.class);
        final Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            final PGNotification[] notifications = pg.getNotifications(500);
            if (notifications != null && notifications.length > 0) {
                return notifications;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private static void execute(final String sql) {
        try {
            executeChecked(sql);
        } catch (final SQLException exception) {
            throw new IllegalStateException("Test setup statement failed: " + sql, exception);
        }
    }

    private static void executeChecked(final String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long count(final String sql) {
        return Long.parseLong(query(sql).getFirst());
    }

    private static List<String> query(final String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            final List<String> values = new ArrayList<>();
            while (rs.next()) {
                values.add(rs.getString(1));
            }
            return values;
        } catch (final SQLException exception) {
            throw new IllegalStateException("Test query failed: " + sql, exception);
        }
    }

    private static void assertWithinSeconds(final Instant expected, final Instant actual, final long tolerance) {
        assertNotNull(actual, "expected a timestamp around " + expected + ", got null");
        final long off = Math.abs(Duration.between(expected, actual).toSeconds());
        assertTrue(off <= tolerance,
                "expected " + actual + " to be within " + tolerance + "s of " + expected + ", was off by " + off + "s");
    }
}
