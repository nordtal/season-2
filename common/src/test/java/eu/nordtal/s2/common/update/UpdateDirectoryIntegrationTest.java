package eu.nordtal.s2.common.update;

import eu.nordtal.s2.common.access.AccessSchema;

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
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises {@link UpdateDirectory} against a real PostgreSQL running the real migrations.
 * <p>
 * Nothing here has an in-memory stand-in. The claim is {@code FOR UPDATE SKIP LOCKED} inside a
 * data-modifying CTE; the countdown is {@code now() + make_interval(...)} evaluated by the database
 * clock; the {@code NOTIFY} rides in the same statement as the {@code INSERT} and either commits
 * with it or not at all. All three are PostgreSQL behaviour, not Java behaviour.
 * </p>
 * <p>
 * Testcontainers is driven by hand from {@link BeforeAll}, like every other integration test in
 * this module - the {@code junit-jupiter} extension is built against JUnit 5 and this repo is on
 * the JUnit 6 BOM - and these tests <b>skip themselves</b> when no Docker daemon is reachable.
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UpdateDirectoryIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private UpdateDirectory updates;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed update tests");

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

    /**
     * Both tables, because one references the other.
     *
     * <p>{@code service_hold.request_id} points at {@code update_request}, and PostgreSQL refuses
     * to truncate a table something references unless the referencing table goes with it. Naming
     * both is the honest version of that - {@code CASCADE} would silently take whatever else grows
     * a foreign key here later.</p>
     */
    private static final String FRESH_INBOX =
            "TRUNCATE TABLE service_hold, update_request RESTART IDENTITY";

    @BeforeEach
    void freshInbox() {
        execute(FRESH_INBOX);
        updates = UpdateDirectory.using(dataSource);
    }

    /**
     * A row written straight into the table, past the one-run rule that {@code submit} enforces.
     * For the tests about what the table does with several open rows - a state the table still
     * allows, and which only submitting refuses.
     */
    private UpdateRequest queued(final UpdateKind kind, final UpdateSource source, final String by,
                                 final Duration delay) {
        try (Connection connection = dataSource.getConnection();
             java.sql.PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO update_request (kind, source, requested_by, not_before) "
                             + "VALUES (?, ?, ?, now() + make_interval(secs => ?)) RETURNING id")) {
            insert.setString(1, kind.name());
            insert.setString(2, source.name());
            insert.setString(3, by);
            insert.setDouble(4, Math.max(0, delay.toSeconds()));
            try (java.sql.ResultSet row = insert.executeQuery()) {
                row.next();
                return updates.find(row.getLong(1)).orElseThrow();
            }
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- submitting

    @Test
    void aSubmittedRequestComesBackAsItWasWritten() {
        final UpdateRequest request =
                updates.submit(UpdateKind.REPORT, UpdateSource.DISCORD, "300000000000000001", Duration.ZERO);

        assertEquals(UpdateKind.REPORT, request.kind());
        assertEquals(UpdateStatus.PENDING, request.status());
        assertEquals(UpdateSource.DISCORD, request.source());
        assertEquals("300000000000000001", request.requestedBy());
        assertNotNull(request.requested());
        assertNull(request.started(), "nothing has claimed it");
        assertNull(request.finished());
        assertNull(request.result());

        assertEquals(request, updates.find(request.id()).orElseThrow(),
                "reading it back gives the same row the insert returned");
    }

    @Test
    @DisplayName("a page size of zero is still a page, and says so in the interface as well")
    void aLimitBelowOneIsStillAPage() {
        // `/api/updates?limit=0` reaches this method as a zero, and what it must not do is come
        // back with nothing while the javadoc promises "at most limit". Either behaviour is
        // defensible; only one of them is written down, and this is the one - clamped, like the
        // journal next door, so the two lists cannot drift apart on a query nobody thinks about.
        queued(UpdateKind.REPORT, UpdateSource.DISCORD, "a", Duration.ZERO);
        final UpdateRequest newest =
                queued(UpdateKind.BACKUP, UpdateSource.CONSOLE, "b", Duration.ZERO);

        assertEquals(List.of(newest.id()), ids(updates.recent(0)));
        assertEquals(List.of(newest.id()), ids(updates.recent(-5)));
        assertEquals(2, updates.recent(10).size());
    }

    private static List<Long> ids(final List<UpdateRequest> requests) {
        return requests.stream().map(UpdateRequest::id).toList();
    }

    @Test
    @DisplayName("every kind the code can name is a kind the CHECK accepts")
    void theEnumAndTheConstraintAgree() {
        // The one thing an in-memory test cannot say anything about. UpdateKind is a Java enum and
        // update_request.kind is a varchar behind a CHECK, and the two are held together by nothing
        // but a migration somebody remembered to write - so a value added to the enum without one
        // compiles, passes every unit test, reaches a real database and is refused there, at the
        // moment somebody presses the button. BACKUP is what V14 added; APPLY is retired and
        // still has to map, because rows carrying it are in the deployed table.
        for (final UpdateKind kind : UpdateKind.values()) {
            final UpdateRequest written =
                    queued(kind, UpdateSource.CONSOLE, null, Duration.ZERO);
            assertEquals(kind, written.kind(), kind + " did not survive the round trip");
        }
    }

    @Test
    void aDelayIsExactlyThatManySecondsOnTheDatabaseClock() {
        // The countdown is why this table exists in the shape it does. make_interval(secs => N) is
        // real seconds - not calendar arithmetic - so the answer must not depend on the time zone
        // the JVM running this test happens to be in.
        final UpdateRequest request = updates.submit(
                UpdateKind.RESTART, UpdateSource.GAME, "Till", Duration.ofSeconds(60));

        final long gap = request.notBefore().getEpochSecond() - request.requested().getEpochSecond();
        assertEquals(60L, gap, "not_before is requested + 60s exactly");
    }

    @Test
    void aNegativeDelayIsTreatedAsNow() {
        // A caller computing a delay from two clocks that disagree gets "now", not an exception on
        // the path that is asking for a restart.
        final UpdateRequest request = updates.submit(
                UpdateKind.RESTART, UpdateSource.DISCORD, null, Duration.ofSeconds(-30));

        assertEquals(request.requested().getEpochSecond(), request.notBefore().getEpochSecond());
        assertNull(request.requestedBy(), "the console has no name and that is allowed");
    }

    @Test
    void theInsertAnnouncesItselfOnTheChannel() throws Exception {
        // Same shape as the phase model: the notification rides in the select list of the
        // statement that writes the row, so it is emitted only for a row that committed.
        try (Connection listener = dataSource.getConnection()) {
            try (Statement statement = listener.createStatement()) {
                statement.execute("LISTEN " + UpdateDirectory.CHANNEL);
            }

            updates.submit(UpdateKind.UPDATE, UpdateSource.CONSOLE, null, Duration.ZERO);

            final PGNotification[] received =
                    listener.unwrap(PGConnection.class).getNotifications(5000);
            assertNotNull(received, "the LISTEN connection was told about the insert");
            assertEquals(1, received.length);
            assertEquals(UpdateDirectory.CHANNEL, received[0].getName());
            assertEquals("", received[0].getParameter(),
                    "no payload, on purpose - a listener must re-read the table");
        }
    }

    /**
     * The bug behind season-2-ops/19's reopening: Till, 2026-09-17, after the mechanics had
     * already been fixed once - "30 Sekunden kam nicht im Chat, nur 10 Sekunden."
     *
     * <p>{@code submit()} rides a {@code pg_notify} in the same statement that writes the row, so
     * the proxy's {@code LISTEN} hears about a fresh request immediately. {@code startCountdown()}
     * did not - it only ever moved {@code not_before} - so the one notification a listener
     * actually receives fires <em>before</em> a plan is even resolved, when the row is not yet
     * counting down at all. The instant that matters, when {@code not_before} becomes
     * {@code now() + 30s}, was announced to nobody, and {@code RestartWatch} had no way to learn of
     * it except its own five-second poll.
     *
     * <p>{@code Countdown#beats} requires {@code millisLeft >= 30_000} to schedule the thirty-second
     * chat line at all - so any of those up to five seconds already spent by the time the poll
     * catches up is that line gone for good, never the ten-second one behind it. This has nothing to
     * do with {@code BACKUP} specifically: every kind that counts down is exposed to it equally,
     * which {@code docker logs nordtal-s2-proxy-1} confirms - every single logged
     * countdown, {@code UPDATE} and {@code BACKUP} alike, shows 12 beats where a full countdown is
     * 13. BACKUP is only the kind Till happened to be testing when he noticed.</p>
     */
    @Test
    @DisplayName("starting a countdown announces itself on the channel, exactly like submitting"
            + " does - season-2-ops/19")
    void startingTheCountdownAnnouncesItselfOnTheChannel() throws Exception {
        final UpdateRequest submitted =
                updates.submit(UpdateKind.BACKUP, UpdateSource.CONSOLE, null, Duration.ZERO);
        updates.claimNext().orElseThrow();

        try (Connection listener = dataSource.getConnection()) {
            try (Statement statement = listener.createStatement()) {
                statement.execute("LISTEN " + UpdateDirectory.CHANNEL);
            }

            updates.startCountdown(submitted.id(), Duration.ofSeconds(30)).orElseThrow();

            final PGNotification[] received =
                    listener.unwrap(PGConnection.class).getNotifications(5000);
            assertNotNull(received, "the LISTEN connection was told the countdown had started -"
                    + " without this, a proxy only learns of it on its next five-second poll, by"
                    + " which time fewer than thirty seconds are left and the chat line for 30 is"
                    + " silently dropped (Countdown#beats requires millisLeft >= 30_000)");
            assertEquals(1, received.length);
            assertEquals(UpdateDirectory.CHANNEL, received[0].getName());
            assertEquals("", received[0].getParameter());
        }
    }

    // ---------------------------------------------------------------- one run at a time

    @Test
    @DisplayName("a second run is refused while the first is pending, and the refusal names it")
    void aSecondRunIsRefusedWhileOneIsPending() {
        final UpdateRequest first = updates.submit(UpdateKind.DOWN, UpdateSource.CONSOLE, "a", Duration.ZERO,
                List.of("smp"));

        final RunRefused refused = assertThrows(RunRefused.class,
                () -> updates.submit(UpdateKind.DOWN, UpdateSource.CONSOLE, "a", Duration.ZERO, List.of("smp")));

        assertEquals(RunRefused.Reason.RUN_OPEN, refused.reason());
        assertEquals(first.id(), refused.open().id());
        assertEquals(1, updates.recent(10).size(), "nothing was written for the second press");
    }

    @Test
    @DisplayName("a running run refuses every source, whatever it asks for")
    void aRunningRunRefusesEveryKindFromEverySource() {
        updates.submit(UpdateKind.UPDATE, UpdateSource.CONSOLE, "a", Duration.ZERO);
        updates.claimNext().orElseThrow();

        for (final UpdateSource source : UpdateSource.values()) {
            assertThrows(RunRefused.class,
                    () -> updates.submit(UpdateKind.REPORT, source, "b", Duration.ZERO), source.name());
        }
    }

    @Test
    void aFinishedRunNoLongerRefusesTheNext() {
        final UpdateRequest first = updates.submit(UpdateKind.UPDATE, UpdateSource.CONSOLE, "a", Duration.ZERO);
        updates.claimNext().orElseThrow();
        updates.finish(first.id(), UpdateStatus.DONE, "{}");

        assertNotNull(updates.submit(UpdateKind.UPDATE, UpdateSource.CONSOLE, "a", Duration.ZERO));
    }

    @Test
    @DisplayName("the open run is named while it waits, while it runs, and not once it is finished")
    void theOpenRunIsNamedUntilItFinishes() {
        assertTrue(updates.open().isEmpty());
        final UpdateRequest run = updates.submit(UpdateKind.UPDATE, UpdateSource.CONSOLE, "a", Duration.ZERO);
        assertEquals(run.id(), updates.open().orElseThrow().id());
        updates.claimNext().orElseThrow();
        assertEquals(UpdateStatus.RUNNING, updates.open().orElseThrow().status());
        updates.finish(run.id(), UpdateStatus.DONE, "{}");
        assertTrue(updates.open().isEmpty());
    }

    @Test
    @DisplayName("taking down a service that is already held is refused, even with no run open")
    void takingDownAHeldServiceIsRefused() {
        final UpdateRequest down = updates.submit(UpdateKind.DOWN, UpdateSource.CONSOLE, "a", Duration.ZERO,
                List.of("smp"));
        updates.claimNext().orElseThrow();
        updates.hold("smp", "a", down.id());
        updates.finish(down.id(), UpdateStatus.DONE, "{}");

        final RunRefused refused = assertThrows(RunRefused.class,
                () -> updates.submit(UpdateKind.DOWN, UpdateSource.GAME, "b", Duration.ZERO, List.of("limbo", "smp")));
        assertEquals(RunRefused.Reason.ALREADY_HELD, refused.reason());
        assertEquals(List.of("smp"), refused.services());

        assertNotNull(updates.submit(UpdateKind.DOWN, UpdateSource.GAME, "b", Duration.ZERO, List.of("limbo")),
                "a service that is not held can still be taken down");
    }

    @Test
    @DisplayName("two presses at the same instant write exactly one run")
    void twoSimultaneousPressesWriteOneRun() throws Exception {
        final java.util.concurrent.CyclicBarrier together = new java.util.concurrent.CyclicBarrier(2);
        final java.util.concurrent.Callable<Boolean> press = () -> {
            together.await();
            try {
                UpdateDirectory.using(dataSource).submit(UpdateKind.DOWN, UpdateSource.CONSOLE, "a",
                        Duration.ZERO, List.of("smp"));
                return true;
            } catch (final RunRefused refused) {
                return false;
            }
        };
        final java.util.concurrent.ExecutorService two = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            final var left = two.submit(press);
            final var right = two.submit(press);
            assertTrue(left.get() ^ right.get(), "exactly one of the two presses is written");
        } finally {
            two.shutdownNow();
        }
        assertEquals(1, updates.recent(10).size());
    }

    // ---------------------------------------------------------------- claiming

    @Test
    void claimingTakesTheOldestDueRequestAndMarksItRunning() {
        final UpdateRequest first = queued(UpdateKind.REPORT, UpdateSource.DISCORD, "a", Duration.ZERO);
        final UpdateRequest second = queued(UpdateKind.UPDATE, UpdateSource.DISCORD, "b", Duration.ZERO);

        final UpdateRequest claimed = updates.claimNext().orElseThrow();
        assertEquals(first.id(), claimed.id(), "oldest first");
        assertEquals(UpdateStatus.RUNNING, claimed.status());
        assertNotNull(claimed.started());

        assertEquals(second.id(), updates.claimNext().orElseThrow().id());
        assertTrue(updates.claimNext().isEmpty(), "and then there is nothing left");
    }

    @Test
    void aRequestThatIsNotDueYetIsNotClaimed() {
        // This is the whole of the countdown: the row exists for a minute before anything may take
        // it, which is the minute the proxy counts down and the minute a cancel has to fit into.
        updates.submit(UpdateKind.RESTART, UpdateSource.GAME, "Till", Duration.ofSeconds(60));

        assertTrue(updates.claimNext().isEmpty(), "not before its time");
        assertTrue(updates.countingDown().isPresent(), "but it is visible to whoever announces it");
    }

    @Test
    void aDueRequestIsClaimedEvenWhenAnEarlierUndueOneExists() {
        // The restart is written first and is due last. A claim ordered only by id would sit on it
        // and starve everything behind it.
        queued(UpdateKind.RESTART, UpdateSource.GAME, "Till", Duration.ofSeconds(60));
        final UpdateRequest report = queued(UpdateKind.REPORT, UpdateSource.DISCORD, "a", Duration.ZERO);

        assertEquals(report.id(), updates.claimNext().orElseThrow().id());
    }

    @Test
    void twoWorkersNeverClaimTheSameRow() throws Exception {
        updates.submit(UpdateKind.REPORT, UpdateSource.DISCORD, "a", Duration.ZERO);

        // Hold the row in an open transaction, the way a second worker that claimed it would.
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement statement = holder.createStatement()) {
                statement.execute("SELECT id FROM update_request WHERE status = 'PENDING' "
                        + "ORDER BY not_before, id LIMIT 1 FOR UPDATE");
            }

            // SKIP LOCKED means this does not block and does not duplicate: it finds nothing else
            // to take. Blocking here would be the failure - the test would hang rather than fail.
            assertTrue(updates.claimNext().isEmpty(),
                    "the locked row is skipped rather than waited for");

            holder.rollback();
        }

        assertTrue(updates.claimNext().isPresent(), "and is available again once the other let go");
    }

    // ---------------------------------------------------------------- finishing

    @Test
    void finishingWritesTheReportIntoTheSameRow() {
        final UpdateRequest submitted = updates.submit(UpdateKind.UPDATE, UpdateSource.DISCORD, "a", Duration.ZERO);
        updates.claimNext().orElseThrow();

        final UpdateRequest finished =
                updates.finish(submitted.id(), UpdateStatus.DONE, "smp  0.1.0 -> 0.2.0").orElseThrow();

        assertEquals(UpdateStatus.DONE, finished.status());
        assertEquals("smp  0.1.0 -> 0.2.0", finished.result());
        assertNotNull(finished.finished());
        assertTrue(finished.status().isFinished());
    }

    @Test
    void onlyARunningRequestCanBeFinished() {
        final UpdateRequest submitted = updates.submit(UpdateKind.UPDATE, UpdateSource.DISCORD, "a", Duration.ZERO);

        assertTrue(updates.finish(submitted.id(), UpdateStatus.DONE, "x").isEmpty(),
                "it was never claimed, so there is no answer to write");

        updates.claimNext().orElseThrow();
        assertTrue(updates.finish(submitted.id(), UpdateStatus.DONE, "x").isPresent());
        assertTrue(updates.finish(submitted.id(), UpdateStatus.FAILED, "y").isEmpty(),
                "and an answer that is already there is not overwritten by a second worker");
    }

    @Test
    void aClaimedRequestCannotBeFinishedAsCancelled() {
        final UpdateRequest submitted = updates.submit(UpdateKind.RESTART, UpdateSource.DISCORD, "a", Duration.ZERO);
        updates.claimNext().orElseThrow();

        // CANCELLED is reachable only through cancelCountdown, which is a person withdrawing one.
        // Letting it in here would mean a worker could report its own work as somebody's cancel.
        assertThrows(IllegalArgumentException.class,
                () -> updates.finish(submitted.id(), UpdateStatus.CANCELLED, "too late"));
        assertThrows(IllegalArgumentException.class,
                () -> updates.finish(submitted.id(), UpdateStatus.RUNNING, "still going"));
    }

    // ---------------------------------------------------------------- cancelling

    @Test
    void aCountdownCanBeStoppedWhileItIsStillRunning() {
        updates.submit(UpdateKind.RESTART, UpdateSource.GAME, "Till", Duration.ofSeconds(60));

        final UpdateRequest cancelled = updates.cancelCountdown("Till changed their mind").orElseThrow();
        assertEquals(UpdateStatus.CANCELLED, cancelled.status());
        assertEquals("Till changed their mind", cancelled.result());

        assertTrue(updates.countingDown().isEmpty(), "and nothing is counting down any more");
        assertTrue(updates.claimNext().isEmpty(), "and no worker will ever pick it up");
    }

    @Test
    @DisplayName("the countdown steward-worker starts is the one the proxy shows and the button stops")
    void theWorkersOwnCountdownIsCancellable() {
        // The whole of V13, driven end to end. The row is written due immediately, claimed, and
        // only then given a countdown - which is the order that stops a run finding nothing new
        // from counting thirty seconds down to everybody playing first.
        final UpdateRequest submitted =
                updates.submit(UpdateKind.UPDATE, UpdateSource.DISCORD, "a", Duration.ZERO);
        assertTrue(updates.countingDown().isEmpty(),
                "a request nobody has resolved yet is not counting down");

        updates.claimNext().orElseThrow();
        assertTrue(updates.countingDown().isEmpty(),
                "and neither is one that has only been claimed");

        final UpdateRequest counting =
                updates.startCountdown(submitted.id(), Duration.ofSeconds(30)).orElseThrow();
        assertEquals(UpdateStatus.RUNNING, counting.status(),
                "a counting-down row is RUNNING, which is why the partial index had to widen");
        assertEquals(submitted.id(), updates.countingDown().orElseThrow().id());

        assertEquals(submitted.id(), updates.cancelCountdown("stop").orElseThrow().id());
        assertFalse(updates.commitCountdown(submitted.id()),
                "and the run must then stop nothing at all");
        assertTrue(updates.finish(submitted.id(), UpdateStatus.DONE, "{}").isEmpty(),
                "the cancellation is the answer; a late finish must not overwrite it");
        assertEquals("stop", updates.find(submitted.id()).orElseThrow().result());
    }

    /**
     * Everything that stops a server counts down, and the list is asked rather than copied.
     *
     * <p><b>The bug this is written against</b> (Till, 2026-09-15, season-2-ops/19): a backup run
     * stopped the network and moved everybody to the waiting room without a word. The reason was
     * {@code countingDown()} naming {@code RESTART} and {@code UPDATE} and not {@code BACKUP} - and
     * that was the <em>second</em> time the list had gone stale, the first being 2026-09-07 when
     * {@code UPDATE} was the one missing.</p>
     *
     * <p>So this test does not restate the list. It asks {@link UpdateKind#stopsServers()} - the
     * same property the worker uses to decide whether there is anything to stop - and requires that
     * every kind answering yes is visible to whoever announces the outage. A new kind that stops
     * servers is therefore covered on the day it is written, which is the only way this stops
     * happening a third time.</p>
     */
    @Test
    @DisplayName("every kind that stops servers is visible to the countdown")
    void everythingThatStopsServersCountsDown() {
        for (final UpdateKind kind : UpdateKind.values()) {
            if (!kind.stopsServers()) {
                continue;
            }
            execute(FRESH_INBOX);
            final UpdateRequest submitted =
                    updates.submit(kind, UpdateSource.GAME, "Till", Duration.ZERO);
            updates.claimNext().orElseThrow();
            updates.startCountdown(submitted.id(), Duration.ofSeconds(30)).orElseThrow();

            assertEquals(submitted.id(), updates.countingDown()
                            .orElseThrow(() -> new AssertionError(kind
                                    + " stops servers and is counting down, but nobody can see it"
                                    + " - players get no warning at all before it fires"))
                            .id(),
                    kind + " has to be the outage the proxy announces");
        }
    }

    /**
     * And every one of them can be called off again.
     *
     * <p>Separate from the test above because the hole was separate: {@code cancelCountdown} kept
     * its own copy of the kind list, so on 2026-09-15 a backup was both unannounceable and
     * unstoppable, and the second half would not have been noticed by fixing the first. "Stop the
     * countdown" answering "there was nothing to stop" is worse than no button.</p>
     */
    @Test
    @DisplayName("every countdown that can be started can be called off")
    void everyCountdownCanBeCalledOff() {
        for (final UpdateKind kind : UpdateKind.values()) {
            if (!kind.stopsServers()) {
                continue;
            }
            execute(FRESH_INBOX);
            final UpdateRequest submitted =
                    updates.submit(kind, UpdateSource.GAME, "Till", Duration.ZERO);
            updates.claimNext().orElseThrow();
            updates.startCountdown(submitted.id(), Duration.ofSeconds(30)).orElseThrow();

            assertEquals(submitted.id(), updates.cancelCountdown("Till changed their mind")
                            .orElseThrow(() -> new AssertionError(kind
                                    + " is counting down and the cancel cannot reach it - the"
                                    + " button would answer \"too late\" while it was still early"))
                            .id());
            assertEquals(UpdateStatus.CANCELLED, updates.find(submitted.id()).orElseThrow().status(),
                    kind + " has to end up withdrawn, not merely unannounced");
        }
    }

    /** The complement: a kind that stops nothing must never make players hear a countdown. */
    @Test
    @DisplayName("a kind that stops nothing is not announced")
    void aReportIsNeverAnnounced() {
        final UpdateRequest submitted =
                updates.submit(UpdateKind.REPORT, UpdateSource.GAME, "Till", Duration.ZERO);
        assertFalse(UpdateKind.REPORT.stopsServers(), "the premise of this test");
        updates.claimNext().orElseThrow();
        updates.startCountdown(submitted.id(), Duration.ofSeconds(30)).orElseThrow();

        assertTrue(updates.countingDown().isEmpty(),
                "a report moves nothing, so counting down to it would be a lie");
    }

    @Test
    @DisplayName("committing the countdown takes it out of the set the cancel can reach")
    void committingEndsTheCancelWindow() {
        final UpdateRequest submitted =
                updates.submit(UpdateKind.UPDATE, UpdateSource.GAME, "Till", Duration.ZERO);
        updates.claimNext().orElseThrow();
        updates.startCountdown(submitted.id(), Duration.ofSeconds(30)).orElseThrow();

        assertTrue(updates.commitCountdown(submitted.id()), "the run holds the right to proceed");
        assertTrue(updates.countingDown().isEmpty(), "nothing is counting down any more");
        assertTrue(updates.cancelCountdown("too late").isEmpty(),
                "which is the sentence the admin needs, and not an error");
        assertEquals(UpdateStatus.RUNNING, updates.find(submitted.id()).orElseThrow().status());
    }

    @Test
    @DisplayName("a countdown cannot be started on a request somebody has already withdrawn")
    void aCancelledRequestGetsNoCountdown() {
        final UpdateRequest submitted =
                updates.submit(UpdateKind.RESTART, UpdateSource.GAME, "Till", Duration.ofSeconds(60));
        updates.cancelCountdown("changed my mind").orElseThrow();

        assertTrue(updates.startCountdown(submitted.id(), Duration.ofSeconds(30)).isEmpty());
        assertEquals(UpdateStatus.CANCELLED, updates.find(submitted.id()).orElseThrow().status());
    }

    @Test
    void cancellingAfterTheRunBeganAnswersEmptyRatherThanLying() {
        // The one answer the admin actually needs: "too late", not "cancelled" on a row that is
        // already stopping servers. A claimed row with no countdown on it is exactly that.
        updates.submit(UpdateKind.RESTART, UpdateSource.DISCORD, "a", Duration.ZERO);
        updates.claimNext().orElseThrow();

        assertTrue(updates.cancelCountdown("too late").isEmpty());
    }

    @Test
    void aReportIsNotCancelledByTheRestartCancel() {
        // "Stop the countdown" must not quietly withdraw somebody else's report. A report has no
        // countdown to stop - it takes nothing down - so it is not in the cancellable set at all.
        final UpdateRequest report = updates.submit(UpdateKind.REPORT, UpdateSource.DISCORD, "a",
                Duration.ZERO);

        assertTrue(updates.cancelCountdown("nope").isEmpty());
        assertEquals(UpdateStatus.PENDING, updates.find(report.id()).orElseThrow().status());
    }

    @Test
    void anUpdateIsCountedDownAndCanBeStopped() {
        // New on 2026-09-07, and it is the half that was missing rather than a refinement. Both
        // countingDown() and cancelCountdown() looked for kind = 'RESTART' alone, which was
        // complete while a restart was the only thing with a countdown on it. An UPDATE now stops
        // servers and carries the same not_before - so the old scope would have counted down before
        // a restart and said NOTHING before the one that also replaces jars, and the button
        // offering to stop it could not have.
        final UpdateRequest update = updates.submit(UpdateKind.UPDATE, UpdateSource.DISCORD, "a",
                Duration.ofSeconds(30));

        assertEquals(update.id(), updates.countingDown().orElseThrow().id(),
                "the proxy counts down towards whatever is about to take servers away");
        assertEquals(update.id(), updates.cancelCountdown("stop").orElseThrow().id());
        assertEquals(UpdateStatus.CANCELLED, updates.find(update.id()).orElseThrow().status());
    }

    @Test
    void theEarlierOfTwoRestartsIsTheOneShownAndTheOneCancelled() {
        final UpdateRequest soon = queued(UpdateKind.RESTART, UpdateSource.GAME, "a", Duration.ofSeconds(60));
        queued(UpdateKind.RESTART, UpdateSource.DISCORD, "b", Duration.ofSeconds(600));

        assertEquals(soon.id(), updates.countingDown().orElseThrow().id());
        assertEquals(soon.id(), updates.cancelCountdown("stop").orElseThrow().id());
        assertTrue(updates.countingDown().isPresent(), "the later one is still standing");
    }

    // ---------------------------------------------------------------- reading the table forward

    @Test
    @DisplayName("the feed reads forward from the last id it drew, and no further back")
    void sinceIsEverythingAfterTheMark() {
        final UpdateRequest first = queued(UpdateKind.REPORT, UpdateSource.GAME, "a", Duration.ZERO);
        final UpdateRequest second = queued(UpdateKind.UPDATE, UpdateSource.CONSOLE, null, Duration.ZERO);

        assertEquals(List.of(first.id(), second.id()),
                updates.since(0L).stream().map(UpdateRequest::id).toList(),
                "zero means everything, which is what a database with no history answers with");
        assertEquals(List.of(second.id()),
                updates.since(first.id()).stream().map(UpdateRequest::id).toList());
        assertEquals(List.of(), updates.since(second.id()),
                "and the ordinary tick, for the whole of a season, answers nothing at all");

        assertEquals(second.id(), updates.latestId(),
                "which is where a restarting bot begins, so it does not post the history again");
    }

    @Test
    void latestIdOfAnEmptyTableIsZero() {
        assertEquals(0L, updates.latestId(),
                "a fresh deployment has no history, and the feed must not be given null to reason"
                        + " about");
    }

    @Test
    @DisplayName("a run that finished while the bot was down is inside the catch-up window")
    void finishedWithinFindsTheRunNobodySaw() {
        final UpdateRequest done = updates.submit(UpdateKind.UPDATE, UpdateSource.GAME, "a", Duration.ZERO);
        updates.claimNext().orElseThrow();
        updates.finish(done.id(), UpdateStatus.DONE, "{}").orElseThrow();

        final UpdateRequest open = updates.submit(UpdateKind.REPORT, UpdateSource.GAME, "b", Duration.ZERO);

        assertEquals(List.of(done.id()),
                updates.finishedWithin(Duration.ofMinutes(12)).stream()
                        .map(UpdateRequest::id).toList(),
                "a request that has not finished is not a result to post");
        assertEquals(List.of(), updates.finishedWithin(Duration.ZERO),
                "and a window of nothing finds nothing, rather than everything");
        assertEquals(UpdateStatus.PENDING, updates.find(open.id()).orElseThrow().status());
    }

    // ---------------------------------------------------------------- orphans

    @Test
    @DisplayName("an orphaned restart is a failure like every other kind, since 2026-09-08")
    void anOrphanedRestartIsAFailureToo() {
        // It was read as SUCCESS until this change, and the inference was right at the time: a
        // RESTART was one redeploy of the whole project asked for over HTTP, which took the
        // container running it down every time by design. A restart now cycles the four Minecraft services one at a time
        // and never stops the worker, so an orphaned one means what every other kind means - the
        // worker died in the middle of it. Reporting that as "the redeploy happened" is the one
        // reading nobody can act on.
        final UpdateRequest restart = updates.submit(UpdateKind.RESTART, UpdateSource.DISCORD, "a", Duration.ZERO);
        updates.claimNext().orElseThrow();

        assertEquals(1, updates.settleOrphans("Killed mid-run"));

        final UpdateRequest read = updates.find(restart.id()).orElseThrow();
        assertEquals(UpdateStatus.FAILED, read.status());
        assertEquals("Killed mid-run", read.result());
        assertNotNull(read.finished());

        assertEquals(0, updates.settleOrphans("x"), "and a second start finds nothing to do");
    }

    @Test
    void anOrphanedUpdateIsAFailureAndSaysSo() {
        final UpdateRequest apply = updates.submit(UpdateKind.UPDATE, UpdateSource.DISCORD, "a", Duration.ZERO);
        updates.claimNext().orElseThrow();

        assertEquals(1, updates.settleOrphans("Killed mid-run"));

        final UpdateRequest read = updates.find(apply.id()).orElseThrow();
        assertEquals(UpdateStatus.FAILED, read.status());
        assertEquals("Killed mid-run", read.result());
    }

    @Test
    void settlingOrphansLeavesPendingWorkAlone() {
        final UpdateRequest waiting = updates.submit(UpdateKind.UPDATE, UpdateSource.DISCORD, "a", Duration.ZERO);

        assertEquals(0, updates.settleOrphans("failed"));
        assertEquals(UpdateStatus.PENDING, updates.find(waiting.id()).orElseThrow().status());
    }

    // ---------------------------------------------------------------- when to wake up

    @Test
    void nextDueIsTheEarliestPendingRowAndNothingElse() {
        assertTrue(updates.nextDue().isEmpty(), "an empty inbox has nothing to wake up for");

        final UpdateRequest restart = queued(UpdateKind.RESTART, UpdateSource.GAME, "a", Duration.ofSeconds(60));
        assertEquals(restart.notBefore(), updates.nextDue().orElseThrow());

        final UpdateRequest now = queued(UpdateKind.REPORT, UpdateSource.DISCORD, "b", Duration.ZERO);
        assertEquals(now.notBefore(), updates.nextDue().orElseThrow(), "the sooner of the two");

        updates.claimNext().orElseThrow();
        assertEquals(restart.notBefore(), updates.nextDue().orElseThrow(),
                "a claimed row is not pending any more, so the restart is next again");
    }

    // ---------------------------------------------------------------- the countdown, as rendered

    @Test
    void secondsUntilDueNeverGoesNegative() {
        final Instant notBefore = Instant.parse("2026-09-01T12:00:00Z");
        final UpdateRequest request = new UpdateRequest(1L, UpdateKind.RESTART, UpdateStatus.PENDING,
                UpdateSource.GAME, "Till", notBefore.minusSeconds(60), notBefore, null, null, null);

        assertEquals(60L, request.secondsUntilDue(notBefore.minusSeconds(60)));
        assertEquals(1L, request.secondsUntilDue(notBefore.minusSeconds(1)));
        assertEquals(0L, request.secondsUntilDue(notBefore));
        assertEquals(0L, request.secondsUntilDue(notBefore.plusSeconds(3600)),
                "a countdown that has run out reads zero, not minus an hour");
    }

    // ---------------------------------------------------------------- the schema itself

    @Test
    void theCheckConstraintsRefuseValuesNoBuildCanRead() {
        final SQLException kind = assertThrows(SQLException.class, () -> executeChecked(
                "INSERT INTO update_request (kind, source) VALUES ('REBOOT', 'DISCORD')"));
        assertTrue(kind.getMessage().contains("update_request_kind_check"), kind.getMessage());

        final SQLException status = assertThrows(SQLException.class, () -> executeChecked(
                "INSERT INTO update_request (kind, source, status) VALUES ('APPLY', 'DISCORD', 'MAYBE')"));
        assertTrue(status.getMessage().contains("update_request_status_check"), status.getMessage());

        final SQLException source = assertThrows(SQLException.class, () -> executeChecked(
                "INSERT INTO update_request (kind, source) VALUES ('APPLY', 'CRON')"));
        assertTrue(source.getMessage().contains("update_request_source_check"), source.getMessage());

        assertFalse(updates.claimNext().isPresent(), "none of the three got in");
    }

    @Test
    void aStatusThisBuildCannotReadIsNotMistakenForPending() {
        // Defensive: an older process writing a status a newer one does not know must not read as
        // "still going to happen".
        assertEquals(UpdateStatus.FAILED, UpdateStatus.fromDatabase("SOMETHING_ELSE"));
        assertEquals(UpdateStatus.FAILED, UpdateStatus.fromDatabase(null));
        assertEquals(UpdateStatus.PENDING, UpdateStatus.fromDatabase("PENDING"));
    }

    // ---------------------------------------------------------------- proving a backup happened

    /**
     * The one volume line that makes a report a backup, and the one that does not.
     *
     * <p>Built through {@link UpdateReports#toJson} rather than written out as a string, because
     * what is being asserted is that the reader and the writer agree - a literal here would pass
     * for as long as somebody remembered to edit it.</p>
     */
    private static UpdateReport report(final UpdateReport.State volume) {
        return UpdateReport.at(UpdateReport.Stage.DONE)
                .with(new UpdateReport.ServiceLine("nordtal-s2_mc-smp", volume,
                        List.of(new UpdateReport.Change("backup", null, "1.2 GiB in 41s")), null))
                .with(new UpdateReport.ServiceLine("smp", UpdateReport.State.HEALTHY, List.of(), null));
    }

    /**
     * A23: every service back, nothing snapshotted, and the row says DONE.
     *
     * <p>This is the shape that made the check necessary. Run 23 reported a successful backup
     * having saved zero volumes, and no surface anywhere drew a difference between that and a night
     * that worked.</p>
     */
    private static UpdateReport reportWithNoVolumes() {
        return UpdateReport.at(UpdateReport.Stage.DONE)
                .with(new UpdateReport.ServiceLine("smp", UpdateReport.State.HEALTHY, List.of(), null));
    }

    @Test
    @DisplayName("a run that finished and saved a volume is the one it answers with")
    void aBackupThatSavedSomethingCounts() {
        final long id = backupRow("DONE", 0.25, UpdateReports.toJson(report(UpdateReport.State.SAVED)));

        final UpdateRequest found = updates.lastSuccessfulBackup(Duration.ofHours(12)).orElseThrow();
        assertEquals(id, found.id());
        assertEquals(UpdateKind.BACKUP, found.kind());
    }

    @Test
    @DisplayName("DONE with nothing saved is not a backup - the A23 case")
    void aRunThatSavedNothingIsNotABackup() {
        backupRow("DONE", 0.25, UpdateReports.toJson(reportWithNoVolumes()));

        assertTrue(updates.lastSuccessfulBackup(Duration.ofHours(12)).isEmpty(),
                "a DONE row whose report lists no saved volume was accepted as a backup. That is"
                        + " exactly run 23: every service healthy, every snapshot missing.");
    }

    @Test
    @DisplayName("DONE with every volume FAILED is not a backup either")
    void aRunWhoseVolumesAllFailedIsNotABackup() {
        backupRow("DONE", 0.25, UpdateReports.toJson(report(UpdateReport.State.FAILED)));

        assertTrue(updates.lastSuccessfulBackup(Duration.ofHours(12)).isEmpty(),
                "the report is read, not just the status column");
    }

    @Test
    @DisplayName("a FAILED run does not count even when its report saved something first")
    void aFailedRunIsNotABackup() {
        // The database dump succeeds before the servers are stopped, so a run that fails later
        // genuinely has a SAVED line in it. The status is what decides here, and it has to:
        // whatever went wrong afterwards, nobody has said the volumes are consistent.
        backupRow("FAILED", 0.25, UpdateReports.toJson(report(UpdateReport.State.SAVED)));

        assertTrue(updates.lastSuccessfulBackup(Duration.ofHours(12)).isEmpty());
    }

    @Test
    @DisplayName("yesterday's backup is outside a window measured in hours")
    void anOldBackupIsOutsideTheWindow() {
        backupRow("DONE", 25.0, UpdateReports.toJson(report(UpdateReport.State.SAVED)));

        assertTrue(updates.lastSuccessfulBackup(Duration.ofHours(12)).isEmpty(),
                "the whole point of the window is that yesterday's backup does not authorise"
                        + " today's reset");
        assertTrue(updates.lastSuccessfulBackup(Duration.ofHours(48)).isPresent(),
                "and the row is still there - it is the window that excluded it, not the filter");
    }

    @Test
    @DisplayName("a result nobody can parse proves nothing")
    void anUnreadableResultIsNotProof() {
        // What a steward-worker older than 2026-09-07 wrote into that column. Every drawing surface
        // falls back to printing this raw; a caller deciding whether a world may be deleted must
        // not, because it cannot tell a saved volume from a sentence.
        backupRow("DONE", 0.25, "Update finished. smp: running");

        assertTrue(updates.lastSuccessfulBackup(Duration.ofHours(12)).isEmpty());
    }

    @Test
    @DisplayName("the newest row that can be proved wins, not simply the newest row")
    void itWalksPastARunItCannotProve() {
        final long good = backupRow("DONE", 20.0, UpdateReports.toJson(report(UpdateReport.State.SAVED)));
        backupRow("DONE", 1.0, UpdateReports.toJson(reportWithNoVolumes()));

        // A LIMIT 1 on the SQL would answer with the one-hour-old row, find nothing saved in it,
        // and report no backup at all - while a provable one sat two rows down inside the window.
        assertEquals(good, updates.lastSuccessfulBackup(Duration.ofHours(24)).orElseThrow().id());
    }

    @Test
    @DisplayName("an update is not a backup, however healthy it came back")
    void onlyBackupsCount() {
        execute("INSERT INTO update_request (kind, source, status, started, finished, result)"
                + " VALUES ('UPDATE', 'DISCORD', 'DONE', now(), now(), $json$"
                + UpdateReports.toJson(report(UpdateReport.State.SAVED)) + "$json$)");

        assertTrue(updates.lastSuccessfulBackup(Duration.ofHours(12)).isEmpty());
    }

    /**
     * Writes a settled {@code BACKUP} row {@code hoursAgo} in the past, on the database's clock.
     *
     * @return the id, so a test can say which row it expected back
     */
    private static long backupRow(final String status, final double hoursAgo, final String result) {
        execute("INSERT INTO update_request (kind, source, status, requested, not_before, started,"
                + " finished, result) VALUES ('BACKUP', 'CONSOLE', '" + status + "',"
                + " now() - make_interval(hours => " + (int) Math.ceil(hoursAgo) + "),"
                + " now() - make_interval(secs => " + (long) (hoursAgo * 3600) + "),"
                + " now() - make_interval(secs => " + (long) (hoursAgo * 3600) + "),"
                + " now() - make_interval(secs => " + (long) (hoursAgo * 3600) + "),"
                + " $json$" + result + "$json$)");
        return lastId();
    }

    @Test
    @DisplayName("a hold survives, refreshes rather than duplicates, and goes away again")
    void aServiceCanBeHeldDown() {
        // season-2-ops/125. The whole reason this state is in the database and not in a field is
        // that it has to outlive a restart of the worker and of the interface, so the only test
        // worth having is one against a real table.
        final UpdateRequest down = updates.submit(
                UpdateKind.DOWN, UpdateSource.CONSOLE, "Till", Duration.ZERO, List.of("smp"));

        updates.hold("smp", "Till", down.id());
        assertTrue(updates.isHeld("smp"));
        assertFalse(updates.isHeld("limbo"), "holding one service held another");

        final ServiceHold held = updates.holds().getFirst();
        assertEquals("smp", held.service());
        assertEquals("Till", held.heldBy());
        assertEquals(down.id(), held.requestId());
        assertNotNull(held.since());

        // Pressing Down on something that is already down is somebody making sure, not an error.
        updates.hold("smp", "Somebody else", down.id());
        assertEquals(1, updates.holds().size(), "the second press wrote a second row");
        assertEquals("Somebody else", updates.holds().getFirst().heldBy());

        updates.release("smp");
        assertEquals(List.of(), updates.holds());
        // Releasing twice is not an error either: the button is idempotent on purpose.
        updates.release("smp");
    }

    @Test
    @DisplayName("a hold whose DOWN row is deleted keeps the hold and forgets the row")
    void theHoldOutlivesItsExplanation() {
        // ON DELETE SET NULL, and it is the deliberate direction: a hold that vanished with its
        // row would leave a service that the next run starts with nobody having asked for it.
        final UpdateRequest down = updates.submit(
                UpdateKind.DOWN, UpdateSource.CONSOLE, "Till", Duration.ZERO, List.of("limbo"));
        updates.hold("limbo", "Till", down.id());

        execute("DELETE FROM update_request WHERE id = " + down.id());

        final ServiceHold held = updates.holds().getFirst();
        assertEquals("limbo", held.service());
        assertNull(held.requestId(), "the hold went away with the row that explained it");
        updates.release("limbo");
    }

    private static long lastId() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             java.sql.ResultSet rows = statement.executeQuery(
                     "SELECT max(id) FROM update_request")) {
            rows.next();
            return rows.getLong(1);
        } catch (final SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void execute(final String sql) {
        try {
            executeChecked(sql);
        } catch (final SQLException failure) {
            throw new IllegalStateException(sql, failure);
        }
    }

    private static void executeChecked(final String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
