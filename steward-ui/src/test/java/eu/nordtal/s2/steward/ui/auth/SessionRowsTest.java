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
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The three one-time values {@code steward_session} carries, against a real PostgreSQL.
 *
 * Why these are not asserted through HTTP like everything else
 * Two of them are about time - a session past its expiry, a ceremony past its window - and
 * the only honest way to reach those through a browser is to wait ten minutes. The rows can be aged
 * instead, which is what {@code expireAt} and {@code ceremonyStartedAt} are for, and which is why
 * those two live on the DAO rather than in a test writing its own SQL: a test with its own
 * {@code UPDATE} is a second place that has to change when a column moves, and the first thing it
 * stops noticing is a column it no longer writes.
 *
 * The third is the {@code RETURNING} trap, and it is asserted here in one line rather than
 * inferred from a sign-in that fails four layers up. See {@code SessionDao#consumeState}.
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
    void consumingAStateAnswersWithTheOldValue() {
        final String id = sessions.begin("the-state");

        // `RETURNING x` after `SET x = NULL` answers the new row, so the naive one-liner hands back empty.
        assertEquals(Optional.of("the-state"), sessions.consumeState(id));
        assertEquals(Optional.empty(), sessions.consumeState(id), "it was readable twice");
    }

    @Test
    void aCeremonyIsOneShotAndHasAClock() {
        final String id = sessions.signIn("42", "Someone", List.of("4711"));
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
    void anExpiredSessionIsGoneBeforeTheSweepTouchesIt() {
        final String id = sessions.signIn("44", "Yesterday", List.of("4711"));
        assertTrue(sessions.find(id).isPresent());

        sessions.expireAt(id, Instant.now().minusSeconds(1));

        // The row is still there - nothing has swept - and it is already not a session.
        assertTrue(sessions.find(id).isEmpty(), "an expired row was handed out as a session");
        assertEquals(1, sessions.sweep(), "and the sweep is what actually removes it");
    }

    @Test
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
