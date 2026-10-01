package eu.nordtal.s2.database.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.DatabaseText;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.messages.Refused;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

/**
 * Exercises {@link UpdateDirectory} against a real PostgreSQL running the real migrations.
 *
 * The claim, the countdown clock and the NOTIFY are database behaviour; tests skip without Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UpdateDirectoryIntegrationTest {
    private static DataSource dataSource;

    private UpdateDirectory updates;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    /** Both tables, since {@code service_hold} references {@code steward_inbox}; {@code CASCADE} would take more. */
    private static final String FRESH_INBOX = "TRUNCATE TABLE service_hold, steward_inbox RESTART IDENTITY";

    @BeforeEach
    void freshInbox() {
        execute(FRESH_INBOX);
        updates = UpdateDirectory.using(dataSource);
    }

    /** Writes a row straight into the table, past the held-service check, withdrawing whatever run was open. */
    private UpdateRequest queued(final UpdateKind kind, final Actor actor, final Duration delay) {
        try (Connection connection = dataSource.getConnection();
                Statement close = connection.createStatement();
                java.sql.PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO steward_inbox (kind, payload, actor_kind, actor_id, scheduled_for) "
                                + "VALUES (?, '{\"services\": []}', ?, ?, now() + make_interval(secs => ?)) RETURNING id")) {
            close.execute("UPDATE steward_inbox SET status = 'CANCELLED', finished = now()"
                    + " WHERE status IN ('PENDING', 'RUNNING')");
            insert.setString(1, kind.name());
            insert.setString(2, actor.kind().name());
            insert.setString(3, actor.id());
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
                updates.submit(UpdateKind.START, Actor.person(DiscordId.of("300000000000000001")), Duration.ZERO);

        assertEquals(UpdateKind.START, request.kind());
        assertEquals(UpdateStatus.PENDING, request.status());
        assertEquals(Actor.person(DiscordId.of("300000000000000001")), request.actor());
        assertNull(request.countdownEnd(), "nothing counts down before steward has a plan");
        assertEquals(List.of(), request.moving());
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
        queued(UpdateKind.START, Actor.HOST, Duration.ZERO);
        final UpdateRequest newest = queued(UpdateKind.BACKUP, Actor.HOST, Duration.ZERO);

        assertEquals(List.of(newest.id()), ids(updates.recent(0)));
        assertEquals(List.of(newest.id()), ids(updates.recent(-5)));
        assertEquals(2, updates.recent(10).size());
    }

    private static List<Long> ids(final List<UpdateRequest> requests) {
        return requests.stream().map(UpdateRequest::id).toList();
    }

    @Test
    void everyKindTheCodeCanNameIsAKindTheCheckAccepts() {
        // Every enum value must pass the kind CHECK.
        for (final UpdateKind kind : UpdateKind.values()) {
            final UpdateRequest written = queued(kind, Actor.HOST, Duration.ZERO);
            assertEquals(kind, written.kind(), kind + " did not survive the round trip");
        }
    }

    @Test
    void aDelayIsExactlyThatManySecondsOnTheDatabaseClock() {
        // make_interval(secs => N) is real seconds, so the answer does not depend on the JVM's time zone.
        final UpdateRequest request = updates.submit(UpdateKind.RESTART, Actor.HOST, Duration.ofSeconds(60));

        final long gap =
                request.scheduledFor().getEpochSecond() - request.requested().getEpochSecond();
        assertEquals(60L, gap, "scheduled_for is requested + 60s exactly");
    }

    @Test
    void aNegativeDelayIsTreatedAsNow() {
        // A delay computed from two disagreeing clocks becomes "now", not an exception on the restart path.
        final UpdateRequest request = updates.submit(UpdateKind.RESTART, Actor.HOST, Duration.ofSeconds(-30));

        assertEquals(
                request.requested().getEpochSecond(), request.scheduledFor().getEpochSecond());
        assertEquals(Actor.HOST, request.actor(), "the host has no Discord id and that is allowed");
    }

    @Test
    void theInsertAnnouncesItselfOnTheChannel() throws Exception {
        // The notification rides in the writing statement, so it is emitted only for a committed row.
        try (Connection listener = dataSource.getConnection()) {
            try (Statement statement = listener.createStatement()) {
                statement.execute("LISTEN " + eu.nordtal.s2.database.notify.Channel.UPDATE.sqlName());
            }

            updates.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);

            final PGNotification[] received =
                    listener.unwrap(PGConnection.class).getNotifications(5000);
            assertNotNull(received, "the LISTEN connection was told about the insert");
            assertEquals(1, received.length);
            assertEquals(eu.nordtal.s2.database.notify.Channel.UPDATE.sqlName(), received[0].getName());
            assertEquals("", received[0].getParameter(), "no payload, on purpose - a listener must re-read the table");
        }
    }

    @Test
    void everyChangeOfARunAnnouncesItselfSoTheFeedsNeedNoPoll() throws Exception {
        final UpdateRequest first = updates.submit(UpdateKind.BACKUP, Actor.HOST, Duration.ZERO);

        try (Connection listener = dataSource.getConnection()) {
            try (Statement statement = listener.createStatement()) {
                statement.execute("LISTEN " + eu.nordtal.s2.database.notify.Channel.UPDATE.sqlName());
            }
            final PGConnection pg = listener.unwrap(PGConnection.class);

            assertTrue(updates.claimNext().isPresent());
            assertTrue(announced(pg), "the claim was not announced");
            assertTrue(updates.progress(first.id(), "{}"));
            assertTrue(announced(pg), "a progress write was not announced");
            assertTrue(updates.handOver(first.id(), "{}"));
            assertTrue(announced(pg), "the handover was not announced");
            assertTrue(updates.claimNext().isPresent());
            assertTrue(announced(pg), "the second claim was not announced");
            assertTrue(updates.commitCountdown(first.id()));
            assertTrue(announced(pg), "the committed countdown was not announced");
            assertTrue(updates.finish(first.id(), UpdateStatus.DONE, "{}").isPresent());
            assertTrue(announced(pg), "the finish was not announced");

            updates.submit(UpdateKind.RESTART, Actor.HOST, Duration.ofMinutes(5));
            assertTrue(announced(pg), "the second request was not announced");
            assertTrue(updates.cancelCountdown("test").isPresent());
            assertTrue(announced(pg), "the cancel was not announced");

            updates.submit(UpdateKind.BACKUP, Actor.HOST, Duration.ZERO);
            assertTrue(updates.claimNext().isPresent());
            assertTrue(announced(pg), "the third request was not announced");
            assertEquals(1, updates.settleOrphans("gone"));
            assertTrue(announced(pg), "settling the orphans was not announced");
        }
    }

    /**
     * Checks that {@code startCountdown()} notifies the channel like {@code submit()} does.
     *
     * Otherwise the proxy learns of the countdown only by its poll, and the thirty-second line can be lost.
     */
    @Test
    void startingTheCountdownAnnouncesItselfOnTheChannel() throws Exception {
        final UpdateRequest submitted = updates.submit(UpdateKind.BACKUP, Actor.HOST, Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());

        try (Connection listener = dataSource.getConnection()) {
            try (Statement statement = listener.createStatement()) {
                statement.execute("LISTEN " + eu.nordtal.s2.database.notify.Channel.UPDATE.sqlName());
            }

            assertTrue(updates.startCountdown(submitted.id(), Duration.ofSeconds(30), List.of("smp"))
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
            assertEquals(eu.nordtal.s2.database.notify.Channel.UPDATE.sqlName(), received[0].getName());
            assertEquals("", received[0].getParameter());
        }
    }

    @Test
    void aSecondRunIsRefusedWhileTheFirstIsPendingAndTheRefusalNamesIt() {
        final UpdateRequest first = updates.submit(UpdateKind.DOWN, Actor.HOST, Duration.ZERO, List.of("smp"));

        final Refused refused = assertThrows(
                Refused.class, () -> updates.submit(UpdateKind.DOWN, Actor.HOST, Duration.ZERO, List.of("smp")));

        assertEquals(UpdateRefusal.RUN_OPEN, refused.reason());
        assertEquals(first.id(), refused.refusal().message().args().get("id"));
        assertEquals(
                "Run #" + first.id() + " is still pending (DOWN).",
                DatabaseText.english(refused.refusal().message()));
        assertEquals(1, updates.recent(10).size(), "nothing was written for the second press");
    }

    @Test
    void aRunningRunRefusesEverySourceWhateverItAsksFor() {
        updates.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());

        for (final Actor actor : List.of(Actor.HOST, Actor.STEWARD, Actor.person(DiscordId.of("300000000000000002")))) {
            assertThrows(
                    Refused.class,
                    () -> updates.submit(UpdateKind.START, actor, Duration.ZERO),
                    actor.kind().name());
        }
    }

    @Test
    void aFinishedRunNoLongerRefusesTheNext() {
        final UpdateRequest first = updates.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());
        updates.finish(first.id(), UpdateStatus.DONE, "{}");

        assertNotNull(updates.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO));
    }

    @Test
    void theOpenRunIsNamedWhileItWaitsWhileItRunsAndNotOnceItIsFinished() {
        assertTrue(updates.open().isEmpty());
        final UpdateRequest run = updates.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);
        assertEquals(run.id(), updates.open().orElseThrow().id());
        assertTrue(updates.claimNext().isPresent());
        assertEquals(UpdateStatus.RUNNING, updates.open().orElseThrow().status());
        updates.finish(run.id(), UpdateStatus.DONE, "{}");
        assertTrue(updates.open().isEmpty());
    }

    @Test
    void takingDownAServiceThatIsAlreadyHeldIsRefusedEvenWithNoRunOpen() {
        final UpdateRequest down = updates.submit(UpdateKind.DOWN, Actor.HOST, Duration.ZERO, List.of("smp"));
        assertTrue(updates.claimNext().isPresent());
        updates.hold("smp", Actor.HOST, down.id());
        updates.finish(down.id(), UpdateStatus.DONE, "{}");

        final Refused refused = assertThrows(
                Refused.class,
                () -> updates.submit(UpdateKind.DOWN, Actor.HOST, Duration.ZERO, List.of("limbo", "smp")));
        assertEquals(UpdateRefusal.ALREADY_HELD, refused.reason());
        assertEquals(
                "Already down: smp.", DatabaseText.english(refused.refusal().message()));

        assertNotNull(
                updates.submit(UpdateKind.DOWN, Actor.HOST, Duration.ZERO, List.of("limbo")),
                "a service that is not held can still be taken down");
    }

    @Test
    void twoPressesAtTheSameInstantWriteExactlyOneRun() throws Exception {
        final java.util.concurrent.CyclicBarrier together = new java.util.concurrent.CyclicBarrier(2);
        final java.util.concurrent.Callable<Boolean> press = () -> {
            together.await();
            try {
                UpdateDirectory.using(dataSource).submit(UpdateKind.DOWN, Actor.HOST, Duration.ZERO, List.of("smp"));
                return true;
            } catch (final Refused refused) {
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
    void claimingTakesTheDueRequestAndMarksItRunning() {
        final UpdateRequest only = queued(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);

        final UpdateRequest claimed = updates.claimNext().orElseThrow();
        assertEquals(only.id(), claimed.id());
        assertEquals(UpdateStatus.RUNNING, claimed.status());
        assertNotNull(claimed.started());
        assertTrue(updates.claimNext().isEmpty(), "and then there is nothing left");
    }

    @Test
    void aRequestThatIsNotDueYetIsNotClaimed() {
        // The row is not claimable for a minute: the minute the proxy counts down and a cancel fits into.
        updates.submit(UpdateKind.RESTART, Actor.HOST, Duration.ofSeconds(60));

        assertTrue(updates.claimNext().isEmpty(), "not before its time");
        assertTrue(updates.countingDown().isPresent(), "but it is visible to whoever announces it");
    }

    @Test
    void theDatabaseItselfRefusesASecondOpenRun() {
        queued(UpdateKind.RESTART, Actor.HOST, Duration.ofSeconds(60));

        // Past the directory, as the host installer writes: the unique index is the one-run rule.
        final SQLException second = assertThrows(
                SQLException.class,
                () -> executeChecked("INSERT INTO steward_inbox (kind, payload, actor_kind)"
                        + " VALUES ('START', '{\"services\": []}', 'HOST')"));
        assertTrue(second.getMessage().contains("steward_inbox_one_open"), second.getMessage());
    }

    @Test
    void twoStewardsNeverClaimTheSameRow() throws Exception {
        updates.submit(UpdateKind.START, Actor.HOST, Duration.ZERO);

        // Hold the row in an open transaction, the way a second steward that claimed it would.
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement statement = holder.createStatement()) {
                statement.execute("SELECT id FROM steward_inbox WHERE status = 'PENDING' "
                        + "ORDER BY scheduled_for, id LIMIT 1 FOR UPDATE");
            }

            // SKIP LOCKED: this finds nothing else and does not block; a block would hang the test.
            assertTrue(updates.claimNext().isEmpty(), "the locked row is skipped rather than waited for");

            holder.rollback();
        }

        assertTrue(updates.claimNext().isPresent(), "and is available again once the other let go");
    }

    @Test
    void finishingWritesTheReportIntoTheSameRow() {
        final UpdateRequest submitted = updates.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);
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
        final UpdateRequest submitted = updates.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);

        assertTrue(
                updates.finish(submitted.id(), UpdateStatus.DONE, "x").isEmpty(),
                "it was never claimed, so there is no answer to write");

        assertTrue(updates.claimNext().isPresent());
        assertTrue(updates.finish(submitted.id(), UpdateStatus.DONE, "x").isPresent());
        assertTrue(
                updates.finish(submitted.id(), UpdateStatus.FAILED, "y").isEmpty(),
                "and an answer that is already there is not overwritten by a second steward");
    }

    @Test
    void aClaimedRequestCannotBeFinishedAsCancelled() {
        final UpdateRequest submitted = updates.submit(UpdateKind.RESTART, Actor.HOST, Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());

        // CANCELLED is a person withdrawing a run, so steward cannot report its own work as one.
        assertThrows(
                IllegalArgumentException.class,
                () -> updates.finish(submitted.id(), UpdateStatus.CANCELLED, "too late"));
        assertThrows(
                IllegalArgumentException.class,
                () -> updates.finish(submitted.id(), UpdateStatus.RUNNING, "still going"));
    }

    @Test
    void aCountdownCanBeStoppedWhileItIsStillRunning() {
        updates.submit(UpdateKind.RESTART, Actor.HOST, Duration.ofSeconds(60));

        final UpdateRequest cancelled =
                updates.cancelCountdown("Till changed their mind").orElseThrow();
        assertEquals(UpdateStatus.CANCELLED, cancelled.status());
        assertEquals("Till changed their mind", cancelled.result());

        assertTrue(updates.countingDown().isEmpty(), "and nothing is counting down any more");
        assertTrue(updates.claimNext().isEmpty(), "and steward will never pick it up");
    }

    @Test
    void theCountdownStewardStartsIsTheOneTheProxyShowsAndTheButtonStops() {
        // Claimed first, counted down after, so a run that finds nothing new never warns anybody.
        final UpdateRequest submitted = updates.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);
        assertTrue(updates.countingDown().isEmpty(), "a request nobody has resolved yet is not counting down");

        assertTrue(updates.claimNext().isPresent());
        assertTrue(updates.countingDown().isEmpty(), "and neither is one that has only been claimed");

        final UpdateRequest counting = updates.startCountdown(submitted.id(), Duration.ofSeconds(30), List.of("smp"))
                .orElseThrow();
        assertEquals(
                UpdateStatus.RUNNING,
                counting.status(),
                "a counting-down row is RUNNING, which is why the partial index had to widen");
        assertEquals(submitted.id(), updates.countingDown().orElseThrow().id());
        // The claim and the countdown are two statements, so a second may pass between them.
        final long afterClaim =
                Duration.between(counting.started(), counting.countdownEnd()).toMillis();
        assertTrue(
                afterClaim >= 30_000 && afterClaim < 32_000,
                "the proxy counts towards countdown_end, 30 s after the countdown started, was " + afterClaim + " ms");
        assertEquals(counting.requested(), counting.scheduledFor(), "the schedule itself never moves");
        assertEquals(List.of("smp"), counting.moving());

        assertEquals(
                submitted.id(), updates.cancelCountdown("stop").orElseThrow().id());
        assertFalse(updates.commitCountdown(submitted.id()), "and the run must then stop nothing at all");
        assertTrue(
                updates.finish(submitted.id(), UpdateStatus.DONE, "{}").isEmpty(),
                "the cancellation is the answer; a late finish must not overwrite it");
        assertEquals("stop", updates.find(submitted.id()).orElseThrow().result());
    }

    @Test
    void aCountdownThatRunsOutLeavesTheRunningRowWithTheServicesItStops() {
        final UpdateRequest submitted = updates.submit(UpdateKind.RESTART, Actor.HOST, Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());
        assertTrue(updates.running().isPresent(), "resolving is running, and moves nobody yet");
        assertEquals(List.of(), updates.running().orElseThrow().moving());

        updates.startCountdown(submitted.id(), Duration.ofSeconds(30), List.of("smp", "limbo"));
        assertTrue(updates.running().isEmpty(), "a countdown can still be cancelled, so nobody is moved");

        assertTrue(updates.commitCountdown(submitted.id()));
        final UpdateRequest running = updates.running().orElseThrow();
        assertEquals(List.of("smp", "limbo"), running.moving());
        assertNotNull(running.countdownEnd());
        assertTrue(updates.countingDown().isEmpty(), "a countdown that ran out is not counting down");
    }

    /** Checks that every kind that stops servers counts down, asking {@link UpdateKind#stopsServers()} for the list. */
    @Test
    void everythingThatStopsServersCountsDown() {
        for (final UpdateKind kind : UpdateKind.values()) {
            if (!kind.stopsServers()) {
                continue;
            }
            execute(FRESH_INBOX);
            final UpdateRequest submitted = updates.submit(kind, Actor.HOST, Duration.ZERO);
            assertTrue(updates.claimNext().isPresent());
            assertTrue(updates.startCountdown(submitted.id(), Duration.ofSeconds(30), List.of("smp"))
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

    /** Checks that every countdown that can be started can be called off again. */
    @Test
    void everyCountdownThatCanBeStartedCanBeCalledOff() {
        for (final UpdateKind kind : UpdateKind.values()) {
            if (!kind.stopsServers()) {
                continue;
            }
            execute(FRESH_INBOX);
            final UpdateRequest submitted = updates.submit(kind, Actor.HOST, Duration.ZERO);
            assertTrue(updates.claimNext().isPresent());
            assertTrue(updates.startCountdown(submitted.id(), Duration.ofSeconds(30), List.of("smp"))
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

    /** Checks that a kind that stops nothing never makes players hear a countdown. */
    @Test
    void aKindThatStopsNothingIsNotAnnounced() {
        final UpdateRequest submitted = updates.submit(UpdateKind.START, Actor.HOST, Duration.ZERO);
        assertFalse(UpdateKind.START.stopsServers(), "the premise of this test");
        assertTrue(updates.claimNext().isPresent());
        assertTrue(updates.startCountdown(submitted.id(), Duration.ofSeconds(30), List.of("smp"))
                .isPresent());

        assertTrue(updates.countingDown().isEmpty(), "a report moves nothing, so counting down to it would be a lie");
    }

    @Test
    void committingTheCountdownTakesItOutOfTheSetTheCancelCanReach() {
        final UpdateRequest submitted = updates.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());
        assertTrue(updates.startCountdown(submitted.id(), Duration.ofSeconds(30), List.of("smp"))
                .isPresent());

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
        final UpdateRequest submitted = updates.submit(UpdateKind.RESTART, Actor.HOST, Duration.ofSeconds(60));
        assertTrue(updates.cancelCountdown("changed my mind").isPresent());

        assertTrue(updates.startCountdown(submitted.id(), Duration.ofSeconds(30), List.of("smp"))
                .isEmpty());
        assertEquals(
                UpdateStatus.CANCELLED,
                updates.find(submitted.id()).orElseThrow().status());
    }

    @Test
    void cancellingAfterTheRunBeganAnswersEmptyRatherThanLying() {
        // A claimed row with no countdown answers "too late", not "cancelled".
        updates.submit(UpdateKind.RESTART, Actor.HOST, Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());

        assertTrue(updates.cancelCountdown("too late").isEmpty());
    }

    @Test
    void aReportIsNotCancelledByTheRestartCancel() {
        // A report has no countdown, so "stop the countdown" cannot withdraw it.
        final UpdateRequest report = updates.submit(UpdateKind.START, Actor.HOST, Duration.ZERO);

        assertTrue(updates.cancelCountdown("nope").isEmpty());
        assertEquals(
                UpdateStatus.PENDING, updates.find(report.id()).orElseThrow().status());
    }

    @Test
    void anUpdateIsCountedDownAndCanBeStopped() {
        // An UPDATE stops servers and counts down like a RESTART, so both must be found and cancellable.
        final UpdateRequest update = updates.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ofSeconds(30));

        assertEquals(
                update.id(),
                updates.countingDown().orElseThrow().id(),
                "the proxy counts down towards whatever is about to take servers away");
        assertEquals(update.id(), updates.cancelCountdown("stop").orElseThrow().id());
        assertEquals(
                UpdateStatus.CANCELLED, updates.find(update.id()).orElseThrow().status());
    }

    @Test
    void theFeedReadsForwardFromTheLastIdItDrewAndNoFurtherBack() {
        final UpdateRequest first = queued(UpdateKind.START, Actor.HOST, Duration.ZERO);
        final UpdateRequest second = queued(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);

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
        final UpdateRequest done = updates.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());
        assertTrue(updates.finish(done.id(), UpdateStatus.DONE, "{}").isPresent());

        final UpdateRequest open = updates.submit(UpdateKind.START, Actor.HOST, Duration.ZERO);

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
        // A restart never stops steward, so an orphaned one means steward died, as for every kind.
        final UpdateRequest restart = updates.submit(UpdateKind.RESTART, Actor.HOST, Duration.ZERO);
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
        final UpdateRequest apply = updates.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());

        assertEquals(1, updates.settleOrphans("Killed mid-run"));

        final UpdateRequest read = updates.find(apply.id()).orElseThrow();
        assertEquals(UpdateStatus.FAILED, read.status());
        assertEquals("Killed mid-run", read.result());
    }

    @Test
    void settlingOrphansLeavesPendingWorkAlone() {
        final UpdateRequest waiting = updates.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);

        assertEquals(0, updates.settleOrphans("failed"));
        assertEquals(
                UpdateStatus.PENDING, updates.find(waiting.id()).orElseThrow().status());
    }

    @Test
    void nextDueIsThePendingRowsTimeAndNothingOnceItIsClaimed() {
        assertTrue(updates.nextDue().isEmpty(), "an empty inbox has nothing to wake up for");

        final UpdateRequest now = queued(UpdateKind.START, Actor.HOST, Duration.ZERO);
        assertEquals(now.scheduledFor(), updates.nextDue().orElseThrow());

        assertTrue(updates.claimNext().isPresent());
        assertTrue(updates.nextDue().isEmpty(), "a claimed row is not pending any more");
    }

    @Test
    void secondsUntilDueNeverGoesNegative() {
        final Instant notBefore = Instant.parse("2026-09-01T12:00:00Z");
        final UpdateRequest request = new UpdateRequest(
                1L,
                UpdateKind.RESTART,
                UpdateStatus.PENDING,
                Actor.HOST,
                notBefore.minusSeconds(60),
                notBefore,
                null,
                List.of(),
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
                () -> executeChecked(
                        "INSERT INTO steward_inbox (kind, payload, actor_kind) VALUES ('REPORT', '{\"services\": []}', 'HOST')"));
        assertTrue(kind.getMessage().contains("steward_inbox_kind_check"), kind.getMessage());

        final SQLException status = assertThrows(
                SQLException.class,
                () -> executeChecked("INSERT INTO steward_inbox (kind, payload, actor_kind, status, finished)"
                        + " VALUES ('UPDATE', '{\"services\": []}', 'HOST', 'MAYBE', now())"));
        assertTrue(status.getMessage().contains("steward_inbox_status_check"), status.getMessage());

        final SQLException actor = assertThrows(
                SQLException.class,
                () -> executeChecked(
                        "INSERT INTO steward_inbox (kind, payload, actor_kind) VALUES ('UPDATE', '{\"services\": []}', 'DISCORD')"));
        assertTrue(actor.getMessage().contains("steward_inbox_actor_kind_check"), actor.getMessage());

        final SQLException unnamed = assertThrows(
                SQLException.class,
                () -> executeChecked(
                        "INSERT INTO steward_inbox (kind, payload, actor_kind) VALUES ('UPDATE', '{\"services\": []}', 'PERSON')"));
        assertTrue(unnamed.getMessage().contains("steward_inbox_actor_id_iff_person"), unnamed.getMessage());

        assertFalse(updates.claimNext().isPresent(), "none of the four got in");
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

    /** Waits up to five seconds for a notification; pgjdbc answers an empty array or null for none. */
    private static boolean announced(final PGConnection pg) throws java.sql.SQLException {
        final PGNotification[] received = pg.getNotifications(5000);
        return received != null && received.length > 0;
    }
}
