package eu.nordtal.season.database.season;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.common.season.SeasonOpening;
import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.access.AccessDirectory;
import eu.nordtal.season.database.access.AccessGrant;
import eu.nordtal.season.database.access.AccessSource;
import eu.nordtal.season.messages.Refused;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Exercises {@link SeasonDirectory} against PostgreSQL with the real migrations. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeasonDirectoryIntegrationTest {

    private static final Actor ADMIN = Actor.person(DiscordId.of("300000000000000001"));
    private static final DiscordId EARLY = DiscordId.of("100000000000000001");
    private static final DiscordId LATE = DiscordId.of("100000000000000002");

    private static DataSource dataSource;

    private SeasonDirectory season;
    private AccessDirectory access;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void freshSeason() {
        execute("TRUNCATE TABLE journal_entry, discord_user CASCADE");
        execute("UPDATE season_state SET launch = NULL, maintenance = false, mode = 'HUNGER_GAMES' WHERE id");
        season = SeasonDirectory.using(dataSource, Clock.systemUTC());
        access = AccessDirectory.using(dataSource, Clock.systemUTC());
    }

    @Test
    void aFreshDatabaseStartsClosedWithoutMaintenanceOnTheHungerGames() {
        // The seed of a migration from nothing, read off a database this class has not touched yet.
        final DataSource untouched = TestDatabase.fresh().dataSource();

        final SeasonState state =
                SeasonDirectory.using(untouched, Clock.systemUTC()).state();

        assertNull(state.launch());
        assertFalse(state.maintenance());
        assertEquals(SeasonMode.HUNGER_GAMES, state.mode());
    }

    @Test
    void settingADateCountsDownAndJournalsItWithTheReason() {
        final Instant at = soon(Duration.ofDays(9));

        final SeasonChange change = season.setLaunch(at, ADMIN, "the weekend");

        assertEquals(at, change.current().launch());
        assertEquals(SeasonOpening.COUNTDOWN, season.state().opening(Instant.now()));
        assertEquals(List.of("SET_LAUNCH_DATE|-|" + at + "|0|the weekend"), journal());
    }

    @Test
    void aDateInThePastIsRefusedAndWritesNothing() {
        final Refused refused = assertThrows(
                Refused.class, () -> season.setLaunch(Instant.now().minus(Duration.ofMinutes(5)), ADMIN, null));

        assertEquals(SeasonRefusal.IN_THE_PAST, refused.reason());
        assertNull(season.state().launch());
        assertEquals(List.of(), journal());
    }

    @Test
    void movingTheDateMovesThePeriodsBoughtBeforeItAndNoOther() {
        final Instant first = soon(Duration.ofDays(10));
        season.setLaunch(first, ADMIN, null);
        final AccessGrant anchored = access.grantAccess(EARLY, 30, AccessSource.PURCHASE, null);
        final AccessGrant stacked = access.grantAccess(EARLY, 30, AccessSource.PURCHASE, null);
        // An account whose run started before the launch, by an admin's hand, is not anchored to it.
        access.grantAccess(LATE, 30, AccessSource.ADMIN, null);
        execute("UPDATE access_grant SET valid_from = now() WHERE discord_id = '" + LATE.value() + "'");

        final Instant later = first.plus(Duration.ofDays(3));
        final SeasonChange change = season.setLaunch(later, ADMIN, null);

        assertEquals(2, change.grants());
        assertEquals(1, change.accounts());
        assertEquals(List.of(later, later.plus(Duration.ofDays(30))), startsOf(EARLY));
        assertEquals(anchored.validUntil().plus(Duration.ofDays(3)), endOf(EARLY, 0));
        assertEquals(stacked.validUntil().plus(Duration.ofDays(3)), endOf(EARLY, 1));
        assertTrue(startsOf(LATE).getFirst().isBefore(first), "the unanchored account keeps its run");
        assertEquals(2, season.anchoredGrants());
    }

    @Test
    void launchingNowStartsTheAnchoredPeriodsNowAndOpensTheSeason() {
        season.setLaunch(soon(Duration.ofDays(4)), ADMIN, null);
        access.grantAccess(EARLY, 30, AccessSource.PURCHASE, null);

        final SeasonChange change = season.launchNow(ADMIN, "ready early");

        assertEquals(1, change.grants());
        assertTrue(season.state().launched(Instant.now()));
        final Instant start = startsOf(EARLY).getFirst();
        assertTrue(Duration.between(start, Instant.now()).abs().toSeconds() < 30, "the period starts now: " + start);
        assertEquals(
                SeasonRefusal.ALREADY_LAUNCHED,
                assertThrows(Refused.class, () -> season.launchNow(ADMIN, null)).reason());
    }

    @Test
    void aClosedSeasonDoesNotLaunchNow() {
        final Refused refused = assertThrows(Refused.class, () -> season.launchNow(ADMIN, null));

        assertEquals(SeasonRefusal.NOT_SET, refused.reason());
        assertNull(season.state().launch());
    }

    @Test
    void closingClearsTheDateAndPausesNoPeriod() {
        season.setLaunch(soon(Duration.ofDays(1)), ADMIN, null);
        season.launchNow(ADMIN, null);
        access.grantAccess(EARLY, 30, AccessSource.PURCHASE, null);
        final List<Instant> before = startsOf(EARLY);

        final SeasonChange change = season.closeSeason(ADMIN, "the map is broken");

        assertNull(change.current().launch());
        assertEquals(SeasonOpening.CLOSED, season.state().opening(Instant.now()));
        assertEquals(before, startsOf(EARLY));
        assertTrue(journal().getLast().startsWith("CLOSE_SEASON|"));
    }

    @Test
    void aDateThatHasNotComeCanBeMovedButNotClosed() {
        season.setLaunch(soon(Duration.ofDays(2)), ADMIN, null);

        final Refused refused = assertThrows(Refused.class, () -> season.closeSeason(ADMIN, null));

        assertEquals(SeasonRefusal.NOT_LAUNCHED, refused.reason());
        assertNotNull(season.state().launch());
    }

    @Test
    void maintenanceIsJournaledOnlyWhenItChangesAndTheLastSpanIsReadBack() {
        season.setMaintenance(true, ADMIN, "the backup");
        final SeasonChange again = season.setMaintenance(true, ADMIN, null);
        season.setMaintenance(false, ADMIN, null);

        assertTrue(again.unchanged());
        assertEquals(2, journal().size());
        final MaintenanceSpan span = season.lastMaintenance().orElseThrow();
        assertNotNull(span.off());
        assertEquals(ADMIN, span.by());
    }

    @Test
    void theModeSwitchIsJournaledWithBothModes() {
        final SeasonChange change = season.setMode(SeasonMode.SMP, ADMIN, null);

        assertEquals(SeasonMode.HUNGER_GAMES, change.previous().mode());
        assertEquals(SeasonMode.SMP, season.state().mode());
        assertEquals(List.of("SWITCH_MODE|HUNGER_GAMES|SMP"), query("""
                SELECT e.action || '|' || m.from_mode || '|' || m.to_mode
                FROM journal_entry e JOIN journal_season_mode m ON m.entry = e.id"""));
        assertTrue(season.setMode(SeasonMode.SMP, ADMIN, null).unchanged());
    }

    @Test
    void everyChangeNotifiesTheSeasonChannel() throws SQLException {
        try (Connection listener = dataSource.getConnection();
                Statement statement = listener.createStatement()) {
            statement.execute("LISTEN nordtal_season");

            season.setMaintenance(true, ADMIN, null);
            statement.execute("SELECT 1");

            final var notifications =
                    listener.unwrap(org.postgresql.PGConnection.class).getNotifications(5000);
            assertNotNull(notifications);
            assertEquals("nordtal_season", notifications[0].getName());
        }
    }

    private static Instant soon(final Duration fromNow) {
        return Instant.now().plus(fromNow).truncatedTo(ChronoUnit.SECONDS);
    }

    /** Each entry as {@code action|from|to|moved|reason} of its launch detail. */
    private static List<String> journal() {
        return query("""
                SELECT e.action || '|' || coalesce(l.from_launch::text, '-') || '|'
                       || coalesce(to_char(l.to_launch AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), '-')
                       || '|' || coalesce(l.moved_grants::text, '-') || '|' || coalesce(l.reason, '-')
                FROM journal_entry e LEFT JOIN journal_season_launch l ON l.entry = e.id
                ORDER BY e.occurred""");
    }

    private static List<Instant> startsOf(final DiscordId account) {
        return instants("valid_from", account);
    }

    private static Instant endOf(final DiscordId account, final int index) {
        return instants("valid_until", account).get(index);
    }

    private static List<Instant> instants(final String column, final DiscordId account) {
        return query("SELECT to_char(" + column + " AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')"
                        + " FROM access_grant WHERE discord_id = '" + account.value() + "' AND revoked IS NULL"
                        + " ORDER BY valid_from")
                .stream()
                .map(Instant::parse)
                .toList();
    }

    private static List<String> query(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            final List<String> out = new java.util.ArrayList<>();
            while (rows.next()) {
                out.add(rows.getString(1));
            }
            return out;
        } catch (final SQLException exception) {
            throw new IllegalStateException("Test query failed: " + sql, exception);
        }
    }

    private static void execute(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (final SQLException exception) {
            throw new IllegalStateException("Test setup statement failed: " + sql, exception);
        }
    }
}
