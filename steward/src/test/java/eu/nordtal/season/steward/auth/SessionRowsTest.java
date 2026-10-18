package eu.nordtal.season.steward.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Tests the three one-time values of {@code steward_session} against PostgreSQL. */
class SessionRowsTest {

    private static Sessions sessions;
    private static DataSource source;

    @BeforeAll
    static void start() {
        // Connects as the role that Steward uses, so a missing grant fails here.
        source = TestDatabase.fresh().dataSourceAs(DatabaseRole.STEWARD);
        sessions = new Sessions(source, Duration.ofDays(30));
    }

    @Test
    void theTableKeepsTheHashOfTheCookieAndNotTheCookie() throws Exception {
        final String id = sessions.signIn(DiscordId.of("46"), "Hashed");

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

        // A plain `RETURNING x` after `SET x = NULL` returns the new, empty value.
        assertEquals(Optional.of("the-state"), sessions.consumeState(id));
        assertEquals(Optional.empty(), sessions.consumeState(id), "it was readable twice");
    }

    @Test
    void aCeremonyIsOneShotAndHasAClock() {
        final String id = sessions.signIn(DiscordId.of("42"), "Someone");
        sessions.startCeremony(id, "{\"challenge\":\"abc\"}");

        assertEquals(Optional.of("{\"challenge\":\"abc\"}"), sessions.consumeCeremony(id));
        assertEquals(
                Optional.empty(),
                sessions.consumeCeremony(id),
                "a challenge that can be answered twice is a challenge whoever saw it can answer");

        // Eleven minutes is past the window.
        sessions.startCeremony(id, "{\"challenge\":\"def\"}");
        sessions.ceremonyStartedAt(id, Instant.now().minus(11, ChronoUnit.MINUTES));
        assertEquals(
                Optional.empty(),
                sessions.consumeCeremony(id),
                "a challenge issued eleven minutes ago was still answerable");
    }

    @Test
    void startingAgainDropsTheOneBefore() {
        final String id = sessions.signIn(DiscordId.of("43"), "Someone Else");
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
        final String id = sessions.signIn(DiscordId.of("44"), "Yesterday");
        assertTrue(sessions.find(id).isPresent());

        sessions.expireAt(id, Instant.now().minusSeconds(1));

        // The row exists until a sweep, but it is no session.
        assertTrue(sessions.find(id).isEmpty(), "an expired row was handed out as a session");
        assertEquals(1, sessions.sweep(), "and the sweep is what actually removes it");
    }

    @Test
    void verificationIsRecordedOnTheRow() {
        final String id = sessions.signIn(DiscordId.of("45"), "Unverified");
        assertFalse(sessions.find(id).orElseThrow().verified(), "a session was verified before anybody touched a key");

        sessions.markVerified(id, Duration.ofHours(1));

        final Sessions.Session after = sessions.find(id).orElseThrow();
        assertTrue(after.verified());
        assertTrue(
                after.verifiedAt().isAfter(Instant.now().minus(1, ChronoUnit.MINUTES)),
                "verified_at is not now(): " + after.verifiedAt());
        assertEquals(Duration.ofHours(1), Duration.between(after.verifiedAt(), after.stepUpUntil()));

        sessions.endStepUp(id);

        final Sessions.Session ended = sessions.find(id).orElseThrow();
        assertNull(ended.stepUpUntil(), "the step-up outlived its end");
        assertTrue(ended.verified(), "ending the step-up closed the door as well");
    }
}
