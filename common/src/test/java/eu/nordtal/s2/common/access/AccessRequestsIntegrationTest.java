package eu.nordtal.s2.common.access;

import eu.nordtal.s2.common.access.AccessRequests.NewAccessRequest;
import eu.nordtal.s2.common.notify.Channels;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises {@link AccessRequests} against a real PostgreSQL running the real migrations
 * (season-2-community/08).
 *
 * <p>Nothing here has an in-memory stand-in, for the same reason {@code UpdateDirectoryIntegrationTest}
 * has none: the claim is {@code FOR UPDATE SKIP LOCKED} inside a data-modifying statement, the
 * patience is {@code now() + make_interval(...)} on the database clock, and the {@code NOTIFY}
 * rides in the same statement as the {@code INSERT} and either commits with it or not at all. All
 * three are PostgreSQL behaviour, not Java behaviour.</p>
 *
 * <p>Testcontainers is driven by hand from {@link BeforeAll}, like every other integration test in
 * this module, and these tests <b>skip themselves</b> when no Docker daemon is reachable.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessRequestsIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private AccessRequests inbox;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed access inbox tests");

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
    void freshInbox() {
        execute("TRUNCATE TABLE access_request RESTART IDENTITY");
        inbox = AccessRequests.on(dataSource);
    }

    private static NewAccessRequest grant(final String subject, final long days) {
        return NewAccessRequest.of(AccessRequestKind.GRANT, subject, days,
                AccessRequestSource.STEWARD, "300000000000000001");
    }

    // ---------------------------------------------------------------- writing

    @Test
    void aSubmittedRequestComesBackAsItWasWritten() {
        final AccessRequest request = inbox.submit(grant("400000000000000002", 30));

        assertEquals(AccessRequestKind.GRANT, request.kind());
        assertEquals(AccessRequestStatus.PENDING, request.status());
        assertEquals("400000000000000002", request.subject());
        assertEquals("30", request.argument());
        assertEquals(30L, request.number());
        assertEquals(AccessRequestSource.STEWARD, request.source());
        assertEquals("300000000000000001", request.requestedBy());
        assertNotNull(request.requested());
        assertNotNull(request.expires());
        assertNull(request.started(), "nothing has claimed it");
        assertNull(request.finished());
        assertNull(request.result());

        assertEquals(request, inbox.outcome(request.id()).orElseThrow(),
                "reading it back gives the same row the insert returned");
    }

    /**
     * The three kinds that carry no number must be writable without one, and asking for one has to
     * fail loudly rather than answering zero - "grant zero days" and "grant nothing was written"
     * are the same silent success otherwise.
     */
    @Test
    void aKindWithNoArgumentHasNoneAndSaysSo() {
        final AccessRequest request = inbox.submit(NewAccessRequest.of(
                AccessRequestKind.UNLINK, "400000000000000002", AccessRequestSource.DISCORD, null));

        assertNull(request.argument());
        assertNull(request.requestedBy(), "a request nobody signed is allowed");
        assertThrows(IllegalStateException.class, request::number);
    }

    @Test
    @DisplayName("every kind and every source the code can name is one the CHECK accepts")
    void theEnumsAndTheConstraintsAgree() {
        for (final AccessRequestKind kind : AccessRequestKind.values()) {
            for (final AccessRequestSource source : AccessRequestSource.values()) {
                final AccessRequest written = inbox.submit(new NewAccessRequest(
                        kind, "400000000000000002", "1", source, null));
                assertEquals(kind, written.kind());
                assertEquals(source, written.source());
            }
        }
    }

    @Test
    void theInsertAnnouncesItselfOnTheChannel() throws Exception {
        // The notification rides in the select list of the statement that writes the row, so it is
        // emitted only for a row that committed - the same shape as the phase and update models.
        try (Connection listener = dataSource.getConnection()) {
            try (Statement statement = listener.createStatement()) {
                statement.execute("LISTEN " + Channels.ACCESS);
            }

            inbox.submit(grant("400000000000000002", 30));

            final PGNotification[] received =
                    listener.unwrap(PGConnection.class).getNotifications(5000);
            assertNotNull(received, "the LISTEN connection was told about the insert");
            assertEquals(1, received.length);
            assertEquals(Channels.ACCESS, received[0].getName());
            assertEquals("", received[0].getParameter(),
                    "no payload, on purpose - a listener must re-read the table");
        }
    }

    // ---------------------------------------------------------------- claiming

    @Test
    void theOldestRowIsClaimedFirstAndOnlyOnce() {
        final AccessRequest first = inbox.submit(grant("400000000000000002", 30));
        final AccessRequest second = inbox.submit(grant("400000000000000003", 7));

        final AccessRequest claimed = inbox.claim().orElseThrow();
        assertEquals(first.id(), claimed.id());
        assertEquals(AccessRequestStatus.RUNNING, claimed.status());
        assertNotNull(claimed.started());

        assertEquals(second.id(), inbox.claim().orElseThrow().id(),
                "the second claim takes the next row, never the one already running");
        assertTrue(inbox.claim().isEmpty(), "and then there is nothing left");
    }

    /**
     * The half of the patience that belongs to the executing side. A bot that wakes up after a long
     * outage must not carry out a grant somebody was already told had not happened.
     */
    @Test
    void anExpiredRowIsNotClaimed() {
        inbox.submit(grant("400000000000000002", 30), Duration.ZERO);

        assertTrue(inbox.claim().isEmpty(),
                "a row past its patience has been given up on; running it now would grant access a"
                        + " second time, long after the asker was told it had not been granted");
    }

    // ---------------------------------------------------------------- settling

    @Test
    void theAnswerIsWrittenBackIntoTheSameRow() {
        final AccessRequest request = inbox.submit(grant("400000000000000002", 30));
        inbox.claim();

        inbox.finish(request.id(), true, "{\"until\":\"2026-10-20T00:00:00Z\"}");

        final AccessRequest settled = inbox.outcome(request.id()).orElseThrow();
        assertEquals(AccessRequestStatus.DONE, settled.status());
        assertTrue(settled.status().settled());
        assertNotNull(settled.finished());
        assertEquals("{\"until\":\"2026-10-20T00:00:00Z\"}", settled.result());
    }

    @Test
    void settlingTwiceWritesOnce() {
        final AccessRequest request = inbox.submit(grant("400000000000000002", 30));
        inbox.claim();

        inbox.finish(request.id(), true, "first");
        inbox.finish(request.id(), false, "second");

        final AccessRequest settled = inbox.outcome(request.id()).orElseThrow();
        assertEquals(AccessRequestStatus.DONE, settled.status());
        assertEquals("first", settled.result(),
                "a second settle of the same row must not overwrite what happened");
    }

    // ---------------------------------------------------------------- expiring

    /**
     * The acceptance the ticket asks for: with no bot running at all, a row stops waiting.
     *
     * <p>Nobody else can do this. The case is precisely "the bot is not there", so the expiry is
     * written by whoever looks - {@link AccessRequests#outcome} sweeps before it reads.</p>
     */
    @Test
    @DisplayName("with nothing running, a row expires rather than waiting for ever")
    void anOverdueRowIsExpiredByWhoeverLooks() {
        final AccessRequest request = inbox.submit(grant("400000000000000002", 30), Duration.ZERO);

        final AccessRequest looked = inbox.outcome(request.id()).orElseThrow();

        assertEquals(AccessRequestStatus.EXPIRED, looked.status());
        assertNotNull(looked.finished());
        assertNull(looked.result(), "nothing ever ran, so there is nothing to report");
    }

    /**
     * The other half of the same race, and the reason the sweep names {@code PENDING}: a row the bot
     * claimed a millisecond ago is work in progress, not an abandoned one.
     */
    @Test
    void theSweepLeavesAClaimedRowAlone() {
        final AccessRequest request = inbox.submit(grant("400000000000000002", 30), Duration.ZERO);
        // Claimed before the patience ran out - which the claim above cannot do, so it is forced
        // here, exactly as a bot that took the row a moment before the deadline would have left it.
        execute("UPDATE access_request SET status = 'RUNNING', started = now() WHERE id = "
                + request.id());

        assertEquals(0, inbox.expireDue());
        assertEquals(AccessRequestStatus.RUNNING, inbox.outcome(request.id()).orElseThrow().status());
    }

    @Test
    void pendingSkipsWhatIsExpiredAndWhatIsRunning() {
        final AccessRequest waiting = inbox.submit(grant("400000000000000002", 30));
        inbox.submit(grant("400000000000000003", 7), Duration.ZERO);
        final AccessRequest running = inbox.submit(grant("400000000000000004", 1));
        execute("UPDATE access_request SET status = 'RUNNING' WHERE id = " + running.id());

        assertEquals(List.of(waiting.id()), inbox.pending().stream().map(AccessRequest::id).toList());
    }

    @Test
    void anUnknownIdIsEmptyRatherThanAnError() {
        assertEquals(Optional.empty(), inbox.outcome(9999L));
    }

    // ---------------------------------------------------------------- housekeeping

    @Test
    void purgeTakesSettledHistoryAndLeavesWork() {
        final AccessRequest settled = inbox.submit(grant("400000000000000002", 30));
        inbox.claim();
        inbox.finish(settled.id(), true, "done");
        execute("UPDATE access_request SET finished = now() - make_interval(days => 90) WHERE id = "
                + settled.id());
        final AccessRequest waiting = inbox.submit(grant("400000000000000003", 7));

        assertEquals(1, inbox.purge(Duration.ofDays(30)));

        assertTrue(inbox.outcome(settled.id()).isEmpty());
        assertFalse(inbox.outcome(waiting.id()).isEmpty(), "a pending row is work, not history");
    }

    private static void execute(final String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (final SQLException failure) {
            throw new IllegalStateException(sql, failure);
        }
    }
}
