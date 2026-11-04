package eu.nordtal.season.database.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

/** Exercises {@link TrackResets} as steward-ui against PostgreSQL with the real migrations. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrackResetsIntegrationTest {

    private static final String ADMIN_ID = "300000000000000001";
    private static final Actor ADMIN = Actor.person(DiscordId.of(ADMIN_ID));

    private static DataSource owner;
    private static TrackResets resets;

    @BeforeAll
    static void startDatabase() {
        final TestDatabase database = TestDatabase.fresh();
        owner = database.dataSource();
        resets = new TrackResets(database.dataSourceAs(DatabaseRole.STEWARD));
    }

    @BeforeEach
    void noResetYet() {
        execute("TRUNCATE TABLE journal_entry CASCADE");
        execute("DELETE FROM smp_reset_request");
        execute("DELETE FROM smp_reset");
        execute("UPDATE season_phase SET fresh_start = NULL");
    }

    @Test
    void aResetStampsBothRowsAndJournalsItsReason() {
        resets.request(ADMIN, "  the test weekend is over ");

        assertEquals(
                1,
                count("SELECT count(*) FROM smp_reset_request request, season_phase phase"
                        + " WHERE request.requested = phase.fresh_start"),
                "an smp of the release before reads season_phase.fresh_start, so both carry the same stamp");
        assertEquals(
                List.of("RESET_TRACK|PERSON|" + ADMIN_ID + "|the test weekend is over"),
                query("SELECT e.action || '|' || e.actor_kind || '|' || e.actor_id || '|' || r.reason"
                        + " FROM journal_entry e JOIN journal_track_reset r ON r.entry = e.id"));
    }

    @Test
    void aResetWithoutAReasonIsJournaledWithoutOne() {
        resets.request(ADMIN, " ");

        assertEquals(List.of("RESET_TRACK"), query("SELECT action FROM journal_entry"));
        assertEquals(0, count("SELECT count(*) FROM journal_track_reset"));
    }

    @Test
    void aResetWaitsUntilSmpAppliedItsStamp() {
        assertFalse(resets.pending(), "no reset was asked for");

        resets.request(ADMIN, null);
        assertTrue(resets.pending(), "smp has applied nothing yet");

        execute("INSERT INTO smp_reset (id, applied) SELECT true, requested FROM smp_reset_request");
        assertFalse(resets.pending());

        resets.request(ADMIN, null);
        assertTrue(resets.pending(), "a second reset is a stamp of its own");
    }

    @Test
    void aResetSignalsSmp() throws SQLException {
        try (Connection listener = owner.getConnection()) {
            try (Statement statement = listener.createStatement()) {
                statement.execute("LISTEN nordtal_smp");
            }

            resets.request(ADMIN, null);

            final PGConnection pg = listener.unwrap(PGConnection.class);
            final Instant deadline = Instant.now().plusSeconds(10);
            PGNotification[] notifications = null;
            while (Instant.now().isBefore(deadline) && (notifications == null || notifications.length == 0)) {
                notifications = pg.getNotifications(500);
            }
            assertNotNull(notifications, "no notification arrived on nordtal_smp within the timeout");
            assertEquals("nordtal_smp", notifications[0].getName());
        }
    }

    private static void execute(final String sql) {
        try (Connection connection = owner.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (final SQLException exception) {
            throw new IllegalStateException("Test setup statement failed: " + sql, exception);
        }
    }

    private static long count(final String sql) {
        return Long.parseLong(query(sql).getFirst());
    }

    private static List<String> query(final String sql) {
        try (Connection connection = owner.getConnection();
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
}
