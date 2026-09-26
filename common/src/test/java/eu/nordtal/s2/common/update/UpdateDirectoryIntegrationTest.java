package eu.nordtal.s2.common.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.s2.common.access.AccessSchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Exercises {@link UpdateDirectory} against a real PostgreSQL running the real migrations.
 *
 * Nothing here has an in-memory stand-in. The claim is {@code FOR UPDATE SKIP LOCKED} inside a
 * data-modifying CTE; the countdown is {@code now() + make_interval(...)} evaluated by the database
 * clock; the {@code NOTIFY} rides in the same statement as the {@code INSERT} and either commits
 * with it or not at all. All three are PostgreSQL behaviour, not Java behaviour.
 *
 * Testcontainers is driven by hand from {@link BeforeAll}, like every other integration test in
 * this module - the {@code junit-jupiter} extension is built against JUnit 5 and this repo is on
 * the JUnit 6 BOM - and these tests <b>skip themselves</b> when no Docker daemon is reachable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UpdateDirectoryIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private UpdateDirectory updates;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
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
     * {@code service_hold.request_id} points at {@code update_request}, and PostgreSQL refuses
     * to truncate a table something references unless the referencing table goes with it. Naming
     * both is the honest version of that - {@code CASCADE} would silently take whatever else grows
     * a foreign key here later.
     */
    private static final String FRESH_INBOX = "TRUNCATE TABLE service_hold, update_request RESTART IDENTITY";

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
    private UpdateRequest queued(
            final UpdateKind kind, final UpdateSource source, final String by, final Duration delay) {
        try (Connection connection = dataSource.getConnection();
                java.sql.PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO update_request (kind, source, requested_by, not_before) "
                                + "VALUES (?, ?, ?, now() + make_interval(secs => ?)) RETURNING id")) {
            insert.setString(1, kind.name());
            insert.setString(2, source.name());
            insert.setString(3, by);
            insert.setDouble(4, (double) Math.max(0, delay.toSeconds()));
            try (java.sql.ResultSet row = insert.executeQuery()) {
                row.next();
                return updates.find(row.getLong(1)).orElseThrow();
            }
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
    }

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

        assertEquals(
                request,
                updates.find(request.id()).orElseThrow(),
                "reading it back gives the same row the insert returned");
    }

    @Test
    void aPageSizeOfZeroIsStillAPageAndSaysSoInTheInterfaceAsWell() {
        // A limit of zero is clamped, like the journal's, so the two lists cannot drift apart.
        queued(UpdateKind.REPORT, UpdateSource.DISCORD, "a", Duration.ZERO);
        final UpdateRequest newest = queued(UpdateKind.BACKUP, UpdateSource.CONSOLE, "b", Duration.ZERO);

        assertEquals(List.of(newest.id()), ids(updates.recent(0)));
        assertEquals(List.of(newest.id()), ids(updates.recent(-5)));
        assertEquals(2, updates.recent(10).size());
    }

    private static List<Long> ids(final List<UpdateRequest> requests) {
        return requests.stream().map(UpdateRequest::id).toList();
    }

    @Test
    void everyKindTheCodeCanNameIsAKindTheCheckAccepts() {
        // Every enum value must pass the kind CHECK; retired APPLY still maps because deployed rows carry it.
        for (final UpdateKind kind : UpdateKind.values()) {
            final UpdateRequest written = queued(kind, UpdateSource.CONSOLE, null, Duration.ZERO);
            assertEquals(kind, written.kind(), kind + " did not survive the round trip");
        }
    }

    @Test
    void aDelayIsExactlyThatManySecondsOnTheDatabaseClock() {
        // make_interval(secs => N) is real seconds, so the answer does not depend on the JVM's time zone.
        final UpdateRequest request =
                updates.submit(UpdateKind.RESTART, UpdateSource.GAME, "Till", Duration.ofSeconds(60));

        final long gap =
                request.notBefore().getEpochSecond() - request.requested().getEpochSecond();
        assertEquals(60L, gap, "not_before is requested + 60s exactly");
    }

    @Test
    void aNegativeDelayIsTreatedAsNow() {
        // A delay computed from two disagreeing clocks becomes "now", not an exception on the restart path.
        final UpdateRequest request =
                updates.submit(UpdateKind.RESTART, UpdateSource.DISCORD, null, Duration.ofSeconds(-30));

        assertEquals(request.requested().getEpochSecond(), request.notBefore().getEpochSecond());
        assertNull(request.requestedBy(), "the console has no name and that is allowed");
    }

    @Test
    void theInsertAnnouncesItselfOnTheChannel() throws Exception {
        // The notification rides in the writing statement, so it is emitted only for a committed row.
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
            assertEquals("", received[0].getParameter(), "no payload, on purpose - a listener must re-read the table");
        }
    }

    /**
     * {@code startCountdown()} announces itself on the channel, exactly like {@code submit()} does.
     *
     * {@code submit()} rides a {@code pg_notify} in the same statement that writes the row, so
     * the proxy's {@code LISTEN} hears about a fresh request immediately. If
     * {@code startCountdown()} did not do the same, the one notification a listener actually
     * receives would fire <em>before</em> a plan is even resolved, when the row is not yet counting
     * down at all - and {@code RestartWatch} would have no way to learn that {@code not_before}
     * became {@code now() + 30s} except its own five-second poll.
     *
     * {@code Countdown#beats} requires {@code millisLeft >= 30_000} to schedule the thirty-second
     * chat line at all, so any of those up to five seconds already spent by the time the poll
     * catches up is that line gone for good, never the ten-second one behind it. This applies to
     * every kind that counts down, not just {@code BACKUP}.
     */
    @Test
    void startingTheCountdownAnnouncesItselfOnTheChannel() throws Exception {
        final UpdateRequest submitted = updates.submit(UpdateKind.BACKUP, UpdateSource.CONSOLE, null, Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());

        try (Connection listener = dataSource.getConnection()) {
            try (Statement statement = listener.createStatement()) {
                statement.execute("LISTEN " + UpdateDirectory.CHANNEL);
            }

            assertTrue(updates.startCountdown(submitted.id(), Duration.ofSeconds(30))
                    .isPresent());

            final PGNotification[] received =
                    listener.unwrap(PGConnection.class).getNotifications(5000);
            assertNotNull(
                    received,
                    "the LISTEN connection was told the countdown had started -"
                            + " without this, a proxy only learns of it on its next five-second poll, by"
                            + " which time fewer than thirty seconds are left and the chat line for 30 is"
                            + " silently dropped (Countdown#beats requires millisLeft >= 30_000)");
            assertEquals(1, received.length);
            assertEquals(UpdateDirectory.CHANNEL, received[0].getName());
            assertEquals("", received[0].getParameter());
        }
    }

    @Test
    void aSecondRunIsRefusedWhileTheFirstIsPendingAndTheRefusalNamesIt() {
        final UpdateRequest first =
                updates.submit(UpdateKind.DOWN, UpdateSource.CONSOLE, "a", Duration.ZERO, List.of("smp"));

        final RunRefused refused = assertThrows(
                RunRefused.class,
                () -> updates.submit(UpdateKind.DOWN, UpdateSource.CONSOLE, "a", Duration.ZERO, List.of("smp")));

        assertEquals(RunRefused.Reason.RUN_OPEN, refused.reason());
        assertEquals(first.id(), refused.open().id());
        assertEquals(1, updates.recent(10).size(), "nothing was written for the second press");
    }

    @Test
    void aRunningRunRefusesEverySourceWhateverItAsksFor() {
        updates.submit(UpdateKind.UPDATE, UpdateSource.CONSOLE, "a", Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());

        for (final UpdateSource source : UpdateSource.values()) {
            assertThrows(
                    RunRefused.class,
                    () -> updates.submit(UpdateKind.REPORT, source, "b", Duration.ZERO),
                    source.name());
        }
    }

    @Test
    void aFinishedRunNoLongerRefusesTheNext() {
        final UpdateRequest first = updates.submit(UpdateKind.UPDATE, UpdateSource.CONSOLE, "a", Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());
        updates.finish(first.id(), UpdateStatus.DONE, "{}");

        assertNotNull(updates.submit(UpdateKind.UPDATE, UpdateSource.CONSOLE, "a", Duration.ZERO));
    }

    @Test
    void theOpenRunIsNamedWhileItWaitsWhileItRunsAndNotOnceItIsFinished() {
        assertTrue(updates.open().isEmpty());
        final UpdateRequest run = updates.submit(UpdateKind.UPDATE, UpdateSource.CONSOLE, "a", Duration.ZERO);
        assertEquals(run.id(), updates.open().orElseThrow().id());
        assertTrue(updates.claimNext().isPresent());
        assertEquals(UpdateStatus.RUNNING, updates.open().orElseThrow().status());
        updates.finish(run.id(), UpdateStatus.DONE, "{}");
        assertTrue(updates.open().isEmpty());
    }

    @Test
    void takingDownAServiceThatIsAlreadyHeldIsRefusedEvenWithNoRunOpen() {
        final UpdateRequest down =
                updates.submit(UpdateKind.DOWN, UpdateSource.CONSOLE, "a", Duration.ZERO, List.of("smp"));
        assertTrue(updates.claimNext().isPresent());
        updates.hold("smp", "a", down.id());
        updates.finish(down.id(), UpdateStatus.DONE, "{}");

        final RunRefused refused = assertThrows(
                RunRefused.class,
                () -> updates.submit(UpdateKind.DOWN, UpdateSource.GAME, "b", Duration.ZERO, List.of("limbo", "smp")));
        assertEquals(RunRefused.Reason.ALREADY_HELD, refused.reason());
        assertEquals(List.of("smp"), refused.services());

        assertNotNull(
                updates.submit(UpdateKind.DOWN, UpdateSource.GAME, "b", Duration.ZERO, List.of("limbo")),
                "a service that is not held can still be taken down");
    }

    @Test
    void twoPressesAtTheSameInstantWriteExactlyOneRun() throws Exception {
        final java.util.concurrent.CyclicBarrier together = new java.util.concurrent.CyclicBarrier(2);
        final java.util.concurrent.Callable<Boolean> press = () -> {
            together.await();
            try {
                UpdateDirectory.using(dataSource)
                        .submit(UpdateKind.DOWN, UpdateSource.CONSOLE, "a", Duration.ZERO, List.of("smp"));
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
        // The row is not claimable for a minute: the minute the proxy counts down and a cancel fits into.
        updates.submit(UpdateKind.RESTART, UpdateSource.GAME, "Till", Duration.ofSeconds(60));

        assertTrue(updates.claimNext().isEmpty(), "not before its time");
        assertTrue(updates.countingDown().isPresent(), "but it is visible to whoever announces it");
    }

    @Test
    void aDueRequestIsClaimedEvenWhenAnEarlierUndueOneExists() {
        // The restart is written first and due last; a claim ordered only by id would starve the rest.
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

            // SKIP LOCKED: this finds nothing else and does not block; a block would hang the test.
            assertTrue(updates.claimNext().isEmpty(), "the locked row is skipped rather than waited for");

            holder.rollback();
        }

        assertTrue(updates.claimNext().isPresent(), "and is available again once the other let go");
    }

    @Test
    void finishingWritesTheReportIntoTheSameRow() {
        final UpdateRequest submitted = updates.submit(UpdateKind.UPDATE, UpdateSource.DISCORD, "a", Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());

        final UpdateRequest finished = updates.finish(submitted.id(), UpdateStatus.DONE, "smp  0.1.0 -> 0.2.0")
                .orElseThrow();

        assertEquals(UpdateStatus.DONE, finished.status());
        assertEquals("smp  0.1.0 -> 0.2.0", finished.result());
        assertNotNull(finished.finished());
        assertTrue(finished.status().isFinished());
    }

    @Test
    void onlyARunningRequestCanBeFinished() {
        final UpdateRequest submitted = updates.submit(UpdateKind.UPDATE, UpdateSource.DISCORD, "a", Duration.ZERO);

        assertTrue(
                updates.finish(submitted.id(), UpdateStatus.DONE, "x").isEmpty(),
                "it was never claimed, so there is no answer to write");

        assertTrue(updates.claimNext().isPresent());
        assertTrue(updates.finish(submitted.id(), UpdateStatus.DONE, "x").isPresent());
        assertTrue(
                updates.finish(submitted.id(), UpdateStatus.FAILED, "y").isEmpty(),
                "and an answer that is already there is not overwritten by a second worker");
    }

    @Test
    void aClaimedRequestCannotBeFinishedAsCancelled() {
        final UpdateRequest submitted = updates.submit(UpdateKind.RESTART, UpdateSource.DISCORD, "a", Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());

        // CANCELLED is a person withdrawing a run, so a worker cannot report its own work as one.
        assertThrows(
                IllegalArgumentException.class,
                () -> updates.finish(submitted.id(), UpdateStatus.CANCELLED, "too late"));
        assertThrows(
                IllegalArgumentException.class,
                () -> updates.finish(submitted.id(), UpdateStatus.RUNNING, "still going"));
    }

    @Test
    void aCountdownCanBeStoppedWhileItIsStillRunning() {
        updates.submit(UpdateKind.RESTART, UpdateSource.GAME, "Till", Duration.ofSeconds(60));

        final UpdateRequest cancelled =
                updates.cancelCountdown("Till changed their mind").orElseThrow();
        assertEquals(UpdateStatus.CANCELLED, cancelled.status());
        assertEquals("Till changed their mind", cancelled.result());

        assertTrue(updates.countingDown().isEmpty(), "and nothing is counting down any more");
        assertTrue(updates.claimNext().isEmpty(), "and no worker will ever pick it up");
    }

    @Test
    void theCountdownStewardWorkerStartsIsTheOneTheProxyShowsAndTheButtonStops() {
        // Claimed first, counted down after, so a run that finds nothing new never warns anybody.
        final UpdateRequest submitted = updates.submit(UpdateKind.UPDATE, UpdateSource.DISCORD, "a", Duration.ZERO);
        assertTrue(updates.countingDown().isEmpty(), "a request nobody has resolved yet is not counting down");

        assertTrue(updates.claimNext().isPresent());
        assertTrue(updates.countingDown().isEmpty(), "and neither is one that has only been claimed");

        final UpdateRequest counting =
                updates.startCountdown(submitted.id(), Duration.ofSeconds(30)).orElseThrow();
        assertEquals(
                UpdateStatus.RUNNING,
                counting.status(),
                "a counting-down row is RUNNING, which is why the partial index had to widen");
        assertEquals(submitted.id(), updates.countingDown().orElseThrow().id());

        assertEquals(
                submitted.id(), updates.cancelCountdown("stop").orElseThrow().id());
        assertFalse(updates.commitCountdown(submitted.id()), "and the run must then stop nothing at all");
        assertTrue(
                updates.finish(submitted.id(), UpdateStatus.DONE, "{}").isEmpty(),
                "the cancellation is the answer; a late finish must not overwrite it");
        assertEquals("stop", updates.find(submitted.id()).orElseThrow().result());
    }

    /**
     * Everything that stops a server counts down, and the list is asked rather than copied.
     *
     * This test does not restate the list. It asks {@link UpdateKind#stopsServers()} - the
     * same property the worker uses to decide whether there is anything to stop - and requires that
     * every kind answering yes is visible to whoever announces the outage. A new kind that stops
     * servers is therefore covered on the day it is written.
     */
    @Test
    void everythingThatStopsServersCountsDown() {
        for (final UpdateKind kind : UpdateKind.values()) {
            if (!kind.stopsServers()) {
                continue;
            }
            execute(FRESH_INBOX);
            final UpdateRequest submitted = updates.submit(kind, UpdateSource.GAME, "Till", Duration.ZERO);
            assertTrue(updates.claimNext().isPresent());
            assertTrue(updates.startCountdown(submitted.id(), Duration.ofSeconds(30))
                    .isPresent());

            assertEquals(
                    submitted.id(),
                    updates.countingDown()
                            .orElseThrow(() -> new AssertionError(kind
                                    + " stops servers and is counting down, but nobody can see it"
                                    + " - players get no warning at all before it fires"))
                            .id(),
                    kind + " has to be the outage the proxy announces");
        }
    }

    /**
     * Every countdown that can be started can be called off again.
     *
     * {@code cancelCountdown} is checked separately because it scopes the kinds by its own query.
     */
    @Test
    void everyCountdownThatCanBeStartedCanBeCalledOff() {
        for (final UpdateKind kind : UpdateKind.values()) {
            if (!kind.stopsServers()) {
                continue;
            }
            execute(FRESH_INBOX);
            final UpdateRequest submitted = updates.submit(kind, UpdateSource.GAME, "Till", Duration.ZERO);
            assertTrue(updates.claimNext().isPresent());
            assertTrue(updates.startCountdown(submitted.id(), Duration.ofSeconds(30))
                    .isPresent());

            assertEquals(
                    submitted.id(),
                    updates.cancelCountdown("Till changed their mind")
                            .orElseThrow(() -> new AssertionError(kind
                                    + " is counting down and the cancel cannot reach it - the"
                                    + " button would answer \"too late\" while it was still early"))
                            .id());
            assertEquals(
                    UpdateStatus.CANCELLED,
                    updates.find(submitted.id()).orElseThrow().status(),
                    kind + " has to end up withdrawn, not merely unannounced");
        }
    }

    /** The complement: a kind that stops nothing must never make players hear a countdown. */
    @Test
    void aKindThatStopsNothingIsNotAnnounced() {
        final UpdateRequest submitted = updates.submit(UpdateKind.REPORT, UpdateSource.GAME, "Till", Duration.ZERO);
        assertFalse(UpdateKind.REPORT.stopsServers(), "the premise of this test");
        assertTrue(updates.claimNext().isPresent());
        assertTrue(
                updates.startCountdown(submitted.id(), Duration.ofSeconds(30)).isPresent());

        assertTrue(updates.countingDown().isEmpty(), "a report moves nothing, so counting down to it would be a lie");
    }

    @Test
    void committingTheCountdownTakesItOutOfTheSetTheCancelCanReach() {
        final UpdateRequest submitted = updates.submit(UpdateKind.UPDATE, UpdateSource.GAME, "Till", Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());
        assertTrue(
                updates.startCountdown(submitted.id(), Duration.ofSeconds(30)).isPresent());

        assertTrue(updates.commitCountdown(submitted.id()), "the run holds the right to proceed");
        assertTrue(updates.countingDown().isEmpty(), "nothing is counting down any more");
        assertTrue(
                updates.cancelCountdown("too late").isEmpty(),
                "which is the sentence the admin needs, and not an error");
        assertEquals(
                UpdateStatus.RUNNING, updates.find(submitted.id()).orElseThrow().status());
    }

    @Test
    void aCountdownCannotBeStartedOnARequestSomebodyHasAlreadyWithdrawn() {
        final UpdateRequest submitted =
                updates.submit(UpdateKind.RESTART, UpdateSource.GAME, "Till", Duration.ofSeconds(60));
        assertTrue(updates.cancelCountdown("changed my mind").isPresent());

        assertTrue(
                updates.startCountdown(submitted.id(), Duration.ofSeconds(30)).isEmpty());
        assertEquals(
                UpdateStatus.CANCELLED,
                updates.find(submitted.id()).orElseThrow().status());
    }

    @Test
    void cancellingAfterTheRunBeganAnswersEmptyRatherThanLying() {
        // A claimed row with no countdown answers "too late", not "cancelled".
        updates.submit(UpdateKind.RESTART, UpdateSource.DISCORD, "a", Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());

        assertTrue(updates.cancelCountdown("too late").isEmpty());
    }

    @Test
    void aReportIsNotCancelledByTheRestartCancel() {
        // A report has no countdown, so "stop the countdown" cannot withdraw it.
        final UpdateRequest report = updates.submit(UpdateKind.REPORT, UpdateSource.DISCORD, "a", Duration.ZERO);

        assertTrue(updates.cancelCountdown("nope").isEmpty());
        assertEquals(
                UpdateStatus.PENDING, updates.find(report.id()).orElseThrow().status());
    }

    @Test
    void anUpdateIsCountedDownAndCanBeStopped() {
        // An UPDATE stops servers and counts down like a RESTART, so both must be found and cancellable.
        final UpdateRequest update =
                updates.submit(UpdateKind.UPDATE, UpdateSource.DISCORD, "a", Duration.ofSeconds(30));

        assertEquals(
                update.id(),
                updates.countingDown().orElseThrow().id(),
                "the proxy counts down towards whatever is about to take servers away");
        assertEquals(update.id(), updates.cancelCountdown("stop").orElseThrow().id());
        assertEquals(
                UpdateStatus.CANCELLED, updates.find(update.id()).orElseThrow().status());
    }

    @Test
    void theEarlierOfTwoRestartsIsTheOneShownAndTheOneCancelled() {
        final UpdateRequest soon = queued(UpdateKind.RESTART, UpdateSource.GAME, "a", Duration.ofSeconds(60));
        queued(UpdateKind.RESTART, UpdateSource.DISCORD, "b", Duration.ofMinutes(10));

        assertEquals(soon.id(), updates.countingDown().orElseThrow().id());
        assertEquals(soon.id(), updates.cancelCountdown("stop").orElseThrow().id());
        assertTrue(updates.countingDown().isPresent(), "the later one is still standing");
    }

    @Test
    void theFeedReadsForwardFromTheLastIdItDrewAndNoFurtherBack() {
        final UpdateRequest first = queued(UpdateKind.REPORT, UpdateSource.GAME, "a", Duration.ZERO);
        final UpdateRequest second = queued(UpdateKind.UPDATE, UpdateSource.CONSOLE, null, Duration.ZERO);

        assertEquals(
                List.of(first.id(), second.id()),
                updates.since(0L).stream().map(UpdateRequest::id).toList(),
                "zero means everything, which is what a database with no history answers with");
        assertEquals(
                List.of(second.id()),
                updates.since(first.id()).stream().map(UpdateRequest::id).toList());
        assertEquals(
                List.of(),
                updates.since(second.id()),
                "and the ordinary tick, for the whole of a season, answers nothing at all");

        assertEquals(
                second.id(),
                updates.latestId(),
                "which is where a restarting bot begins, so it does not post the history again");
    }

    @Test
    void latestIdOfAnEmptyTableIsZero() {
        assertEquals(
                0L,
                updates.latestId(),
                "a fresh deployment has no history, and the feed must not be given null to reason" + " about");
    }

    @Test
    void aRunThatFinishedWhileTheBotWasDownIsInsideTheCatchUpWindow() {
        final UpdateRequest done = updates.submit(UpdateKind.UPDATE, UpdateSource.GAME, "a", Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());
        assertTrue(updates.finish(done.id(), UpdateStatus.DONE, "{}").isPresent());

        final UpdateRequest open = updates.submit(UpdateKind.REPORT, UpdateSource.GAME, "b", Duration.ZERO);

        assertEquals(
                List.of(done.id()),
                updates.finishedWithin(Duration.ofMinutes(12)).stream()
                        .map(UpdateRequest::id)
                        .toList(),
                "a request that has not finished is not a result to post");
        assertEquals(
                List.of(),
                updates.finishedWithin(Duration.ZERO),
                "and a window of nothing finds nothing, rather than everything");
        assertEquals(UpdateStatus.PENDING, updates.find(open.id()).orElseThrow().status());
    }

    @Test
    void anOrphanedRestartIsAFailureLikeEveryOtherKindSince20260908() {
        // A restart never stops the worker, so an orphaned one means the worker died, as for every kind.
        final UpdateRequest restart = updates.submit(UpdateKind.RESTART, UpdateSource.DISCORD, "a", Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());

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
        assertTrue(updates.claimNext().isPresent());

        assertEquals(1, updates.settleOrphans("Killed mid-run"));

        final UpdateRequest read = updates.find(apply.id()).orElseThrow();
        assertEquals(UpdateStatus.FAILED, read.status());
        assertEquals("Killed mid-run", read.result());
    }

    @Test
    void settlingOrphansLeavesPendingWorkAlone() {
        final UpdateRequest waiting = updates.submit(UpdateKind.UPDATE, UpdateSource.DISCORD, "a", Duration.ZERO);

        assertEquals(0, updates.settleOrphans("failed"));
        assertEquals(
                UpdateStatus.PENDING, updates.find(waiting.id()).orElseThrow().status());
    }

    @Test
    void nextDueIsTheEarliestPendingRowAndNothingElse() {
        assertTrue(updates.nextDue().isEmpty(), "an empty inbox has nothing to wake up for");

        final UpdateRequest restart = queued(UpdateKind.RESTART, UpdateSource.GAME, "a", Duration.ofSeconds(60));
        assertEquals(restart.notBefore(), updates.nextDue().orElseThrow());

        final UpdateRequest now = queued(UpdateKind.REPORT, UpdateSource.DISCORD, "b", Duration.ZERO);
        assertEquals(now.notBefore(), updates.nextDue().orElseThrow(), "the sooner of the two");

        assertTrue(updates.claimNext().isPresent());
        assertEquals(
                restart.notBefore(),
                updates.nextDue().orElseThrow(),
                "a claimed row is not pending any more, so the restart is next again");
    }

    @Test
    void secondsUntilDueNeverGoesNegative() {
        final Instant notBefore = Instant.parse("2026-09-01T12:00:00Z");
        final UpdateRequest request = new UpdateRequest(
                1L,
                UpdateKind.RESTART,
                UpdateStatus.PENDING,
                UpdateSource.GAME,
                "Till",
                notBefore.minusSeconds(60),
                notBefore,
                null,
                null,
                null);

        assertEquals(60L, request.secondsUntilDue(notBefore.minusSeconds(60)));
        assertEquals(1L, request.secondsUntilDue(notBefore.minusSeconds(1)));
        assertEquals(0L, request.secondsUntilDue(notBefore));
        assertEquals(
                0L,
                request.secondsUntilDue(notBefore.plusSeconds(3600)),
                "a countdown that has run out reads zero, not minus an hour");
    }

    @Test
    void theCheckConstraintsRefuseValuesNoBuildCanRead() {
        final SQLException kind = assertThrows(
                SQLException.class,
                () -> executeChecked("INSERT INTO update_request (kind, source) VALUES ('REBOOT', 'DISCORD')"));
        assertTrue(kind.getMessage().contains("update_request_kind_check"), kind.getMessage());

        final SQLException status = assertThrows(
                SQLException.class,
                () -> executeChecked(
                        "INSERT INTO update_request (kind, source, status) VALUES ('APPLY', 'DISCORD', 'MAYBE')"));
        assertTrue(status.getMessage().contains("update_request_status_check"), status.getMessage());

        final SQLException source = assertThrows(
                SQLException.class,
                () -> executeChecked("INSERT INTO update_request (kind, source) VALUES ('APPLY', 'CRON')"));
        assertTrue(source.getMessage().contains("update_request_source_check"), source.getMessage());

        assertFalse(updates.claimNext().isPresent(), "none of the three got in");
    }

    @Test
    void aStatusThisBuildCannotReadIsNotMistakenForPending() {
        // A status a newer process does not know must not read as "still going to happen".
        assertEquals(UpdateStatus.FAILED, UpdateStatus.fromDatabase("SOMETHING_ELSE"));
        assertEquals(UpdateStatus.FAILED, UpdateStatus.fromDatabase(null));
        assertEquals(UpdateStatus.PENDING, UpdateStatus.fromDatabase("PENDING"));
    }

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
