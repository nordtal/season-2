package eu.nordtal.season.steward.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The three one-time values {@code steward_session} carries, against a real PostgreSQL.
 *
 * Rows are aged through the DAO rather than waited out, and the third value is the {@code RETURNING} trap.
 */
class SessionRowsTest {

    private static Sessions sessions;
    private static DataSource source;

    @BeforeAll
    static void start() {
        // The role steward logs in as, so a statement it was never granted fails here first.
        source = TestDatabase.fresh().dataSourceAs(DatabaseRole.STEWARD);
        sessions = new Sessions(source, Duration.ofDays(30));
    }

    @Test
    void theTableKeepsTheHashOfTheCookieAndNotTheCookie() throws Exception {
        final String id = sessions.signIn(DiscordId.of("46"), "Hashed", List.of("4711"));

        assertEquals(0, count("SELECT count(*) FROM steward_session WHERE id = '" + id + "'"));
        assertEquals(1, count("SELECT count(*) FROM steward_session WHERE id = '" + Sessions.stored(id) + "'"));
        assertEquals(id, sessions.find(id).orElseThrow().id(), "the session is answered under the cookie's id");
    }

    @Test
    void aStartedSignInLivesForMinutesNotForTheSessionsLifetime() throws Exception {
        final String id = sessions.begin("a-state");

        assertEquals(
                1,
                count("SELECT count(*) FROM steward_session WHERE id = '" + Sessions.stored(id)
                        + "' AND expires_at - created_at <= interval '10 minutes'"));
    }

    private static long count(final String sql) throws Exception {
        try (var connection = source.getConnection();
                var statement = connection.createStatement();
                var row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    @Test
    void consumingAStateAnswersWithTheOldValue() {
        final String id = sessions.begin("the-state");

        // `RETURNING x` after `SET x = NULL` answers the new row, so the naive one-liner hands back empty.
        assertEquals(Optional.of("the-state"), sessions.consumeState(id));
        assertEquals(Optional.empty(), sessions.consumeState(id), "it was readable twice");
    }

    @Test
    void aCeremonyIsOneShotAndHasAClock() {
        final String id = sessions.signIn(DiscordId.of("42"), "Someone", List.of("4711"));
        sessions.startCeremony(id, "{\"challenge\":\"abc\"}");

        assertEquals(Optional.of("{\"challenge\":\"abc\"}"), sessions.consumeCeremony(id));
        assertEquals(
                Optional.empty(),
                sessions.consumeCeremony(id),
                "a challenge that can be answered twice is a challenge whoever saw it can answer");

        // Eleven minutes is past the window; the browser's own dialog gives two.
        sessions.startCeremony(id, "{\"challenge\":\"def\"}");
        sessions.ceremonyStartedAt(id, Instant.now().minus(11, ChronoUnit.MINUTES));
        assertEquals(
                Optional.empty(),
                sessions.consumeCeremony(id),
                "a challenge issued eleven minutes ago was still answerable");
    }

    @Test
    void startingAgainDropsTheOneBefore() {
        final String id = sessions.signIn(DiscordId.of("43"), "Someone Else", List.of("4711"));
        sessions.startCeremony(id, "first");
        sessions.startCeremony(id, "second");

        assertEquals(
                Optional.of("second"),
                sessions.consumeCeremony(id),
                "two challenges were outstanding at once, and the older one is the one somebody"
                        + " else may have seen");
    }

    @Test
    void anExpiredSessionIsGoneBeforeTheSweepTouchesIt() {
        final String id = sessions.signIn(DiscordId.of("44"), "Yesterday", List.of("4711"));
        assertTrue(sessions.find(id).isPresent());

        sessions.expireAt(id, Instant.now().minusSeconds(1));

        // The row is still there, since nothing has swept, and it is already not a session.
        assertTrue(sessions.find(id).isEmpty(), "an expired row was handed out as a session");
        assertEquals(1, sessions.sweep(), "and the sweep is what actually removes it");
    }

    @Test
    void verificationIsRecordedOnTheRow() {
        final String id = sessions.signIn(DiscordId.of("45"), "Unverified", List.of("4711"));
        assertFalse(sessions.find(id).orElseThrow().verified(), "a session was verified before anybody touched a key");

        sessions.markVerified(id);

        final Sessions.Session after = sessions.find(id).orElseThrow();
        assertTrue(after.verified());
        assertTrue(
                after.verifiedAt().isAfter(Instant.now().minus(1, ChronoUnit.MINUTES)),
                "verified_at is not now(): " + after.verifiedAt());
    }
}
