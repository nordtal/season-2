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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        // Grants cascade off the user. Without this, the date tests below move each other's rows
        // and every count is the running total of the whole class.
        execute("TRUNCATE TABLE discord_user CASCADE");
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

    // ---------------------------------------------------------------- the two dates

    @Test
    void bothDatesAreEmptyOnAFreshDatabase() {
        // NULL is a real state and not a defect: the phase works without either date, and every
        // surface that would show one says so instead.
        assertTrue(phases.launch().isEmpty());
        assertTrue(phases.smpStart().isEmpty());
    }

    @Test
    void eachDateIsReadBackFromTheRowItWasWrittenTo() {
        execute("UPDATE season_phase SET launch = timestamptz '2026-10-01 18:00+02',"
                + " smp_start = timestamptz '2026-10-08 18:00+02' WHERE id");

        assertEquals(Instant.parse("2026-10-01T16:00:00Z"), phases.launch().orElseThrow());
        assertEquals(Instant.parse("2026-10-08T16:00:00Z"), phases.smpStart().orElseThrow());
    }

    @Test
    void theOpeningAndTheStartOfPaidTimeAreIndependent() {
        // They are a week apart in life - the network opens into the hunger games and paid access
        // only starts running at the SMP - so setting one must not touch the other. A single date
        // reused for both is exactly the bug V9 exists to prevent.
        execute("UPDATE season_phase SET launch = timestamptz '2026-10-01 18:00+02' WHERE id");

        assertTrue(phases.launch().isPresent());
        assertTrue(phases.smpStart().isEmpty());
    }

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

    // ---------------------------------------------------------------- the two dates

    @Test
    void settingTheLaunchDateWritesItAndFilesAnAuditEntry() {
        final Instant when = Instant.now().plus(Duration.ofDays(14));

        final DateChange change = phases.setLaunch(when, ADMIN_ID);

        assertNull(change.previous());
        assertWithinSeconds(when, change.current(), 1);
        assertWithinSeconds(when, phases.launch().orElseThrow(), 1);
        assertEquals(0, change.grants(), "launch owns no access_grant rows");
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'SET_LAUNCH'"));
    }

    @Test
    void clearingALaunchDateGoesBackToNoDateAnnounced() {
        phases.setLaunch(Instant.now().plus(Duration.ofDays(14)), ADMIN_ID);

        final DateChange change = phases.setLaunch(null, ADMIN_ID);

        assertNotNull(change.previous());
        assertNull(change.current());
        assertTrue(phases.launch().isEmpty(), "the column should be NULL again");
    }

    @Test
    void aDateInThePastIsRefusedAndWritesNothing() {
        final Instant past = Instant.now().minus(Duration.ofHours(1));

        assertThrows(SeasonDateRefused.class, () -> phases.setLaunch(past, ADMIN_ID));

        assertTrue(phases.launch().isEmpty());
        assertEquals(0, count("SELECT count(*) FROM audit_log"), "a refusal writes no audit entry");
    }

    @Test
    void theTwoDatesMayNotCrossEachOther() {
        phases.setLaunch(Instant.now().plus(Duration.ofDays(10)), ADMIN_ID);

        // The SMP cannot start running before the network it runs on is open.
        assertThrows(SeasonDateRefused.class,
                () -> phases.setSmpStart(Instant.now().plus(Duration.ofDays(3)), ADMIN_ID));
        // And the opening cannot be moved past a start that is already announced.
        phases.setSmpStart(Instant.now().plus(Duration.ofDays(17)), ADMIN_ID);
        assertThrows(SeasonDateRefused.class,
                () -> phases.setLaunch(Instant.now().plus(Duration.ofDays(20)), ADMIN_ID));
    }

    // ---------------------------------------------------------------- what moves with smp_start

    @Test
    void settingTheDateForTheFirstTimeMovesAccessThatWasSoldWithoutOne() {
        // Exactly the state the shop is deliberately allowed to be in: sold before the season had
        // a date, so the period started at now().
        user("400000000000000001");
        grant("400000000000000001", "now()", "now() + make_interval(hours => 720)");
        final Instant opening = Instant.now().plus(Duration.ofDays(20));

        final DateChange change = phases.setSmpStart(opening, ADMIN_ID);

        assertEquals(1, change.grants());
        assertEquals(1, change.accounts());
        assertWithinSeconds(opening, validFrom("400000000000000001"), 2);
        assertWithinSeconds(opening.plus(Duration.ofDays(30)), validUntil("400000000000000001"), 2);
    }

    @Test
    void stackedPurchasesStayStackedRatherThanCollapsingOntoTheDate() {
        // Two thirty-day purchases the append rule chained back to back. Moving the anchor must
        // move the pair, not put both of them on the opening day.
        user("400000000000000002");
        grant("400000000000000002", "now()", "now() + make_interval(hours => 720)");
        // Anchored on the first period's real end rather than on now() a second time - two
        // statements see two different now()s, and the append rule this imitates chains on
        // max(valid_until) for exactly that reason.
        final String endOfTheFirst = "(SELECT max(valid_until) FROM access_grant"
                + " WHERE discord_id = '400000000000000002')";
        grant("400000000000000002", endOfTheFirst, endOfTheFirst + " + make_interval(hours => 720)");
        final Instant opening = Instant.now().plus(Duration.ofDays(20));

        final DateChange change = phases.setSmpStart(opening, ADMIN_ID);

        assertEquals(2, change.grants());
        assertEquals(1, change.accounts(), "one person, two periods");
        assertWithinSeconds(opening, earliestFrom("400000000000000002"), 2);
        assertWithinSeconds(opening.plus(Duration.ofDays(60)),
                latestUntil("400000000000000002"), 2);
        // Nothing but the earliest period may start anywhere other than where the earliest one
        // ends - which with two periods is the whole of "still stacked, and with no gap".
        assertEquals(0, count("SELECT count(*) FROM access_grant later"
                + " WHERE later.discord_id = '400000000000000002'"
                + "   AND later.valid_from <> (SELECT min(valid_from) FROM access_grant"
                + "                            WHERE discord_id = '400000000000000002')"
                + "   AND later.valid_from <> (SELECT min(valid_until) FROM access_grant"
                + "                            WHERE discord_id = '400000000000000002')"),
                "the second period must still begin exactly where the first one ends");
    }

    @Test
    void twoPeopleWhoBoughtOnDifferentDaysBothStartWhenTheSmpOpens() {
        // The case a single table-wide delta gets wrong: shifting everything by one amount would
        // leave the later buyer starting later than the opening.
        user("400000000000000003");
        user("400000000000000004");
        grant("400000000000000003", "now() - make_interval(hours => 120)", "now() + make_interval(hours => 600)");
        grant("400000000000000004", "now()", "now() + make_interval(hours => 720)");
        final Instant opening = Instant.now().plus(Duration.ofDays(20));

        final DateChange change = phases.setSmpStart(opening, ADMIN_ID);

        assertEquals(2, change.grants());
        assertEquals(2, change.accounts());
        assertWithinSeconds(opening, validFrom("400000000000000003"), 2);
        assertWithinSeconds(opening, validFrom("400000000000000004"), 2);
        // Each keeps the length they paid for, which is not the same for the two of them.
        assertWithinSeconds(opening.plus(Duration.ofDays(30)), validUntil("400000000000000003"), 2);
        assertWithinSeconds(opening.plus(Duration.ofDays(30)), validUntil("400000000000000004"), 2);
    }

    @Test
    void movingAnAnnouncedDateShiftsEverythingAnchoredToItByTheSameAmount() {
        final Instant first = Instant.now().plus(Duration.ofDays(10));
        phases.setSmpStart(first, ADMIN_ID);
        user("400000000000000005");
        grant("400000000000000005",
                "(SELECT smp_start FROM season_phase WHERE id)",
                "(SELECT smp_start FROM season_phase WHERE id) + make_interval(hours => 720)");

        final Instant moved = first.plus(Duration.ofDays(7));
        final DateChange change = phases.setSmpStart(moved, ADMIN_ID);

        assertEquals(1, change.grants());
        assertWithinSeconds(moved, validFrom("400000000000000005"), 2);
        assertWithinSeconds(moved.plus(Duration.ofDays(30)), validUntil("400000000000000005"), 2);
    }

    @Test
    void aRevokedGrantIsLeftWhereItIs() {
        user("400000000000000006");
        grant("400000000000000006", "now()", "now() + make_interval(hours => 720)");
        execute("UPDATE access_grant SET revoked = now() WHERE discord_id = '400000000000000006'");
        final Instant before = validFrom("400000000000000006");

        final DateChange change = phases.setSmpStart(Instant.now().plus(Duration.ofDays(20)), ADMIN_ID);

        assertEquals(0, change.grants(), "a revoked grant no longer counts and must not move");
        assertWithinSeconds(before, validFrom("400000000000000006"), 1);
    }

    @Test
    void clearingTheDateMovesNothing() {
        phases.setSmpStart(Instant.now().plus(Duration.ofDays(10)), ADMIN_ID);
        user("400000000000000007");
        grant("400000000000000007",
                "(SELECT smp_start FROM season_phase WHERE id)",
                "(SELECT smp_start FROM season_phase WHERE id) + make_interval(hours => 720)");
        final Instant before = validFrom("400000000000000007");

        final DateChange change = phases.setSmpStart(null, ADMIN_ID);

        assertNull(change.current());
        assertEquals(0, change.grants(), "there is no date left to anchor them to");
        assertWithinSeconds(before, validFrom("400000000000000007"), 1);
    }

    @Test
    void writingTheSameDateTwiceMovesNothingTheSecondTime() {
        final Instant opening = Instant.now().plus(Duration.ofDays(20));
        user("400000000000000008");
        grant("400000000000000008", "now()", "now() + make_interval(hours => 720)");
        assertEquals(1, phases.setSmpStart(opening, ADMIN_ID).grants());

        final DateChange again = phases.setSmpStart(opening, ADMIN_ID);

        assertTrue(again.unchanged());
        assertEquals(0, again.grants(), "everything is already anchored to that instant");
    }

    @Test
    void onceTheSeasonIsRunningTheDateIsRefused() {
        phases.switchPhase(SeasonPhase.SMP, ADMIN_ID, "test");

        assertThrows(SeasonDateRefused.class,
                () -> phases.setSmpStart(Instant.now().plus(Duration.ofDays(5)), ADMIN_ID));

        assertTrue(phases.smpStart().isEmpty());
    }

    // ---------------------------------------------------------------- helpers

    private static void user(final String discordId) {
        execute("INSERT INTO discord_user (discord_id) VALUES ('" + discordId + "')"
                + " ON CONFLICT DO NOTHING");
    }

    /** {@code from} and {@code until} are SQL expressions, so a test can anchor on the row itself. */
    private static void grant(final String discordId, final String from, final String until) {
        execute("INSERT INTO access_grant (discord_id, valid_from, valid_until, source)"
                + " VALUES ('" + discordId + "', " + from + ", " + until + ", 'ADMIN')");
    }

    private static Instant validFrom(final String discordId) {
        return earliestFrom(discordId);
    }

    private static Instant validUntil(final String discordId) {
        return latestUntil(discordId);
    }

    private static Instant earliestFrom(final String discordId) {
        return instant("SELECT min(valid_from) FROM access_grant WHERE discord_id = '"
                + discordId + "'");
    }

    private static Instant latestUntil(final String discordId) {
        return instant("SELECT max(valid_until) FROM access_grant WHERE discord_id = '"
                + discordId + "'");
    }

    private static Instant instant(final String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next(), "no row for: " + sql);
            final OffsetDateTime value = rs.getObject(1, OffsetDateTime.class);
            return value == null ? null : value.toInstant();
        } catch (final SQLException exception) {
            throw new IllegalStateException("Test query failed: " + sql, exception);
        }
    }

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
