package eu.nordtal.s2.steward.ui.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The three one-time values {@code steward_session} carries, against a real PostgreSQL.
 *
 * <h2>Why these are not asserted through HTTP like everything else</h2>
 * Two of them are about <em>time</em> - a session past its expiry, a ceremony past its window - and
 * the only honest way to reach those through a browser is to wait ten minutes. The rows can be aged
 * instead, which is what {@code expireAt} and {@code ceremonyStartedAt} are for, and which is why
 * those two live on the DAO rather than in a test writing its own SQL: a test with its own
 * {@code UPDATE} is a second place that has to change when a column moves, and the first thing it
 * stops noticing is a column it no longer writes.
 *
 * <p>The third is the {@code RETURNING} trap, and it is asserted here in one line rather than
 * inferred from a sign-in that fails four layers up. See {@code SessionDao#consumeState}.</p>
 */
class SessionRowsTest {

    private static PostgreSQLContainer<?> postgres;
    private static Sessions sessions;

    @BeforeAll
    static void start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "no docker daemon - skipping");
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();
        Flyway.configure(SessionRowsTest.class.getClassLoader())
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        final PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        sessions = new Sessions(source, Duration.ofDays(30));
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @DisplayName("the one-time state comes back as the value that was there, not the NULL just written")
    void consumingAStateAnswersWithTheOldValue() {
        final String id = sessions.begin("the-state");

        // THE WHOLE OF THE TRAP IN ONE ASSERTION. `UPDATE ... SET x = NULL ... RETURNING x` answers
        // with the NEW row, so the obvious one-liner hands back an empty Optional while updating
        // the right row and reporting no error at all. Every sign-in ended at "this sign-in did not
        // start in this browser", and nothing in any log said why.
        assertEquals(Optional.of("the-state"), sessions.consumeState(id));
        assertEquals(Optional.empty(), sessions.consumeState(id), "it was readable twice");
    }

    @Test
    @DisplayName("a ceremony is readable exactly once, and only inside its ten minutes")
    void aCeremonyIsOneShotAndHasAClock() {
        final String id = sessions.signIn("42", "Someone", List.of("4711"));
        sessions.startCeremony(id, "{\"challenge\":\"abc\"}");

        assertEquals(Optional.of("{\"challenge\":\"abc\"}"), sessions.consumeCeremony(id));
        assertEquals(
                Optional.empty(),
                sessions.consumeCeremony(id),
                "a challenge that can be answered twice is a challenge whoever saw it can answer");

        // And the clock. Eleven minutes is past the window; the browser's own dialog gives two, so
        // this only ever catches a ceremony nobody is still looking at.
        sessions.startCeremony(id, "{\"challenge\":\"def\"}");
        sessions.ceremonyStartedAt(id, Instant.now().minus(11, ChronoUnit.MINUTES));
        assertEquals(
                Optional.empty(),
                sessions.consumeCeremony(id),
                "a challenge issued eleven minutes ago was still answerable");
    }

    @Test
    @DisplayName("a second start replaces the first, so only the ceremony on screen can be finished")
    void startingAgainDropsTheOneBefore() {
        final String id = sessions.signIn("43", "Someone Else", List.of("4711"));
        sessions.startCeremony(id, "first");
        sessions.startCeremony(id, "second");

        assertEquals(
                Optional.of("second"),
                sessions.consumeCeremony(id),
                "two challenges were outstanding at once, and the older one is the one somebody"
                        + " else may have seen");
    }

    @Test
    @DisplayName("an expired session is not found, whether the sweep has run or not")
    void anExpiredSessionIsGoneBeforeTheSweepTouchesIt() {
        final String id = sessions.signIn("44", "Yesterday", List.of("4711"));
        assertTrue(sessions.find(id).isPresent());

        sessions.expireAt(id, Instant.now().minusSeconds(1));

        // The row is still there - nothing has swept - and it is already not a session. That is
        // the point of `expires_at > now()` being in the WHERE of the lookup rather than left to
        // the hourly sweep, which is allowed to be late and is allowed to have died.
        assertTrue(sessions.find(id).isEmpty(), "an expired row was handed out as a session");
        assertEquals(1, sessions.sweep(), "and the sweep is what actually removes it");
    }

    @Test
    @DisplayName("a session starts unverified and stays that way until a key is held")
    void verificationIsRecordedOnTheRow() {
        final String id = sessions.signIn("45", "Unverified", List.of("4711"));
        assertFalse(sessions.find(id).orElseThrow().verified(), "a session was verified before anybody touched a key");

        sessions.markVerified(id);

        final Sessions.Session after = sessions.find(id).orElseThrow();
        assertTrue(after.verified());
        assertTrue(
                after.verifiedAt().isAfter(Instant.now().minus(1, ChronoUnit.MINUTES)),
                "verified_at is not now(): " + after.verifiedAt());
    }
}
