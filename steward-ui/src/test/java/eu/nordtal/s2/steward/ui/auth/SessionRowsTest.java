package eu.nordtal.s2.steward.ui.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.TestDatabase;
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

    @BeforeAll
    static void start() {
        final DataSource source = TestDatabase.fresh().dataSource();
        sessions = new Sessions(source, Duration.ofDays(30));
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
