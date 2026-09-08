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

    @BeforeEach
    void freshInbox() {
        execute("TRUNCATE TABLE update_request RESTART IDENTITY");
        updates = UpdateDirectory.using(dataSource);
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

    // ---------------------------------------------------------------- claiming

    @Test
    void claimingTakesTheOldestDueRequestAndMarksItRunning() {
        final UpdateRequest first = updates.submit(UpdateKind.REPORT, UpdateSource.DISCORD, "a", Duration.ZERO);
        final UpdateRequest second = updates.submit(UpdateKind.UPDATE, UpdateSource.DISCORD, "b", Duration.ZERO);

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
        updates.submit(UpdateKind.RESTART, UpdateSource.GAME, "Till", Duration.ofSeconds(60));
        final UpdateRequest report = updates.submit(UpdateKind.REPORT, UpdateSource.DISCORD, "a", Duration.ZERO);

        assertEquals(report.id(), updates.claimNext().orElseThrow().id());
    }

    @Test
    void twoUpdatersNeverClaimTheSameRow() throws Exception {
        updates.submit(UpdateKind.REPORT, UpdateSource.DISCORD, "a", Duration.ZERO);

        // Hold the row in an open transaction, the way a second updater that claimed it would.
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
                "and an answer that is already there is not overwritten by a second updater");
    }

    @Test
    void aClaimedRequestCannotBeFinishedAsCancelled() {
        final UpdateRequest submitted = updates.submit(UpdateKind.RESTART, UpdateSource.DISCORD, "a", Duration.ZERO);
        updates.claimNext().orElseThrow();

        // CANCELLED is reachable only through cancelCountdown, which is a person withdrawing one.
        // Letting it in here would mean an updater could report its own work as somebody's cancel.
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
        assertTrue(updates.claimNext().isEmpty(), "and no updater will ever pick it up");
    }

    @Test
    @DisplayName("the countdown the updater starts is the one the proxy shows and the button stops")
    void theUpdatersOwnCountdownIsCancellable() {
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
        final UpdateRequest soon = updates.submit(UpdateKind.RESTART, UpdateSource.GAME, "a", Duration.ofSeconds(60));
        updates.submit(UpdateKind.RESTART, UpdateSource.DISCORD, "b", Duration.ofSeconds(600));

        assertEquals(soon.id(), updates.countingDown().orElseThrow().id());
        assertEquals(soon.id(), updates.cancelCountdown("stop").orElseThrow().id());
        assertTrue(updates.countingDown().isPresent(), "the later one is still standing");
    }

    // ---------------------------------------------------------------- orphans

    @Test
    @DisplayName("an orphaned restart is a failure like every other kind, since 2026-09-08")
    void anOrphanedRestartIsAFailureToo() {
        // It was read as SUCCESS until this change, and the inference was right at the time: a
        // RESTART was one Arcane redeploy of the whole project, which took the container running it
        // down every time by design. A restart now cycles the four Minecraft services one at a time
        // and never stops the updater, so an orphaned one means what every other kind means - the
        // updater died in the middle of it. Reporting that as "the redeploy happened" is the one
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

        final UpdateRequest restart = updates.submit(UpdateKind.RESTART, UpdateSource.GAME, "a", Duration.ofSeconds(60));
        assertEquals(restart.notBefore(), updates.nextDue().orElseThrow());

        final UpdateRequest now = updates.submit(UpdateKind.REPORT, UpdateSource.DISCORD, "b", Duration.ZERO);
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
