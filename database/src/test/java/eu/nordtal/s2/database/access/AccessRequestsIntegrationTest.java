package eu.nordtal.s2.database.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.access.AccessRequests.NewAccessRequest;
import eu.nordtal.s2.database.notify.Channels;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

/**
 * Exercises {@link AccessRequests} against a real PostgreSQL running the real migrations.
 *
 * The claim, the patience and the {@code NOTIFY} are PostgreSQL behaviour; the tests skip themselves without Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessRequestsIntegrationTest {
    private static DataSource dataSource;

    private AccessRequests inbox;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void freshInbox() {
        execute("TRUNCATE TABLE access_request RESTART IDENTITY");
        inbox = AccessRequests.on(dataSource);
    }

    private static NewAccessRequest grant(final String subject, final long days) {
        return NewAccessRequest.of(
                AccessRequestKind.GRANT, subject, days, Actor.person(DiscordId.of("300000000000000001")));
    }

    @Test
    void aSubmittedRequestComesBackAsItWasWritten() {
        final AccessRequest request = inbox.submit(grant("400000000000000002", 30));

        assertEquals(AccessRequestKind.GRANT, request.kind());
        assertEquals(AccessRequestStatus.PENDING, request.status());
        assertEquals("400000000000000002", request.subject());
        assertEquals("30", request.argument());
        assertEquals(30L, request.number());
        assertEquals(Actor.person(DiscordId.of("300000000000000001")), request.actor());
        assertNotNull(request.requested());
        assertNotNull(request.expires());
        assertNull(request.started(), "nothing has claimed it");
        assertNull(request.finished());
        assertNull(request.result());

        assertEquals(
                request,
                inbox.outcome(request.id()).orElseThrow(),
                "reading it back gives the same row the insert returned");
    }

    /** Checks that the kinds without a number are writable without one and refuse to answer one. */
    @Test
    void aKindWithNoArgumentHasNoneAndSaysSo() {
        final AccessRequest request =
                inbox.submit(NewAccessRequest.of(AccessRequestKind.UNLINK, "400000000000000002", Actor.HOST));

        assertNull(request.argument());
        assertEquals(Actor.HOST, request.actor(), "a request without a person is allowed");
        assertThrows(IllegalStateException.class, request::number);
    }

    @Test
    void everyKindAndEveryActorKindTheCodeCanNameIsOneTheCheckAccepts() {
        for (final AccessRequestKind kind : AccessRequestKind.values()) {
            for (final Actor.Kind actorKind : Actor.Kind.values()) {
                final Actor actor = actorKind == Actor.Kind.PERSON
                        ? Actor.person(DiscordId.of("300000000000000001"))
                        : new Actor(actorKind, null);
                final AccessRequest written =
                        inbox.submit(new NewAccessRequest(kind, "400000000000000002", "1", actor));
                assertEquals(kind, written.kind());
                assertEquals(actor, written.actor());
            }
        }
    }

    @Test
    void theInsertAnnouncesItselfOnTheChannel() throws Exception {
        // The notification is emitted in the writing statement, so only for a committed row.
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
            assertEquals("", received[0].getParameter(), "no payload, on purpose - a listener must re-read the table");
        }
    }

    @Test
    void theOldestRowIsClaimedFirstAndOnlyOnce() {
        final AccessRequest first = inbox.submit(grant("400000000000000002", 30));
        final AccessRequest second = inbox.submit(grant("400000000000000003", 7));

        final AccessRequest claimed = inbox.claim().orElseThrow();
        assertEquals(first.id(), claimed.id());
        assertEquals(AccessRequestStatus.RUNNING, claimed.status());
        assertNotNull(claimed.started());

        assertEquals(
                second.id(),
                inbox.claim().orElseThrow().id(),
                "the second claim takes the next row, never the one already running");
        assertTrue(inbox.claim().isEmpty(), "and then there is nothing left");
    }

    /** Checks that a bot waking after an outage does not carry out an expired request. */
    @Test
    void anExpiredRowIsNotClaimed() {
        inbox.submit(grant("400000000000000002", 30), Duration.ZERO);

        assertTrue(
                inbox.claim().isEmpty(),
                "a row past its patience has been given up on; running it now would grant access a"
                        + " second time, long after the asker was told it had not been granted");
    }

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
        assertEquals("first", settled.result(), "a second settle of the same row must not overwrite what happened");
    }

    /**
     * Checks that a row stops waiting with no bot running, since {@link AccessRequests#outcome} sweeps before it reads.
     */
    @Test
    void withNothingRunningARowExpiresRatherThanWaitingForEver() {
        final AccessRequest request = inbox.submit(grant("400000000000000002", 30), Duration.ZERO);

        final AccessRequest looked = inbox.outcome(request.id()).orElseThrow();

        assertEquals(AccessRequestStatus.EXPIRED, looked.status());
        assertNotNull(looked.finished());
        assertNull(looked.result(), "nothing ever ran, so there is nothing to report");
    }

    /** Checks that the sweep leaves a request the bot has already claimed alone. */
    @Test
    void theSweepLeavesAClaimedRowAlone() {
        final AccessRequest request = inbox.submit(grant("400000000000000002", 30), Duration.ZERO);
        // Forced, as a bot that claimed the row just before the deadline would have left it.
        execute("UPDATE access_request SET status = 'RUNNING', started = now() WHERE id = " + request.id());

        assertEquals(0, inbox.expireDue());
        assertEquals(
                AccessRequestStatus.RUNNING,
                inbox.outcome(request.id()).orElseThrow().status());
    }

    @Test
    void pendingSkipsWhatIsExpiredAndWhatIsRunning() {
        final AccessRequest waiting = inbox.submit(grant("400000000000000002", 30));
        inbox.submit(grant("400000000000000003", 7), Duration.ZERO);
        final AccessRequest running = inbox.submit(grant("400000000000000004", 1));
        execute("UPDATE access_request SET status = 'RUNNING' WHERE id = " + running.id());

        assertEquals(
                List.of(waiting.id()),
                inbox.pending().stream().map(AccessRequest::id).toList());
    }

    @Test
    void anUnknownIdIsEmptyRatherThanAnError() {
        assertEquals(Optional.empty(), inbox.outcome(9999L));
    }

    @Test
    void purgeTakesSettledHistoryAndLeavesWork() {
        final AccessRequest settled = inbox.submit(grant("400000000000000002", 30));
        inbox.claim();
        inbox.finish(settled.id(), true, "done");
        execute("UPDATE access_request SET finished = now() - make_interval(days => 90) WHERE id = " + settled.id());
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
