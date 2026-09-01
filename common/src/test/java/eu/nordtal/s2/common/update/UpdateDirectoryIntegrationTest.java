package eu.nordtal.s2.common.update;

import eu.nordtal.s2.common.access.AccessSchema;

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

            updates.submit(UpdateKind.APPLY, UpdateSource.CONSOLE, null, Duration.ZERO);

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
        final UpdateRequest second = updates.submit(UpdateKind.APPLY, UpdateSource.DISCORD, "b", Duration.ZERO);

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
        assertTrue(updates.pendingRestart().isPresent(), "but it is visible to whoever announces it");
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
        final UpdateRequest submitted = updates.submit(UpdateKind.APPLY, UpdateSource.DISCORD, "a", Duration.ZERO);
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
        final UpdateRequest submitted = updates.submit(UpdateKind.APPLY, UpdateSource.DISCORD, "a", Duration.ZERO);

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

        // CANCELLED is reachable only from PENDING. Letting it in here would mean an updater could
        // "cancel" a restart it had already begun.
        assertThrows(IllegalArgumentException.class,
                () -> updates.finish(submitted.id(), UpdateStatus.CANCELLED, "too late"));
        assertThrows(IllegalArgumentException.class,
                () -> updates.finish(submitted.id(), UpdateStatus.RUNNING, "still going"));
    }

    // ---------------------------------------------------------------- cancelling

    @Test
    void aCountdownCanBeStoppedWhileItIsStillRunning() {
        updates.submit(UpdateKind.RESTART, UpdateSource.GAME, "Till", Duration.ofSeconds(60));

        final UpdateRequest cancelled = updates.cancelPendingRestart("Till changed their mind").orElseThrow();
        assertEquals(UpdateStatus.CANCELLED, cancelled.status());
        assertEquals("Till changed their mind", cancelled.result());

        assertTrue(updates.pendingRestart().isEmpty(), "and nothing is counting down any more");
        assertTrue(updates.claimNext().isEmpty(), "and no updater will ever pick it up");
    }

    @Test
    void cancellingAfterTheRestartStartedAnswersEmptyRatherThanLying() {
        // The one answer the admin actually needs: "too late", not "cancelled" on a row that is
        // already redeploying the network.
        updates.submit(UpdateKind.RESTART, UpdateSource.DISCORD, "a", Duration.ZERO);
        updates.claimNext().orElseThrow();

        assertTrue(updates.cancelPendingRestart("too late").isEmpty());
    }

    @Test
    void aReportIsNotCancelledByTheRestartCancel() {
        // /smp update restart cancel must not quietly withdraw somebody else's apply.
        final UpdateRequest report = updates.submit(UpdateKind.APPLY, UpdateSource.DISCORD, "a", Duration.ZERO);

        assertTrue(updates.cancelPendingRestart("nope").isEmpty());
        assertEquals(UpdateStatus.PENDING, updates.find(report.id()).orElseThrow().status());
    }

    @Test
    void theEarlierOfTwoRestartsIsTheOneShownAndTheOneCancelled() {
        final UpdateRequest soon = updates.submit(UpdateKind.RESTART, UpdateSource.GAME, "a", Duration.ofSeconds(60));
        updates.submit(UpdateKind.RESTART, UpdateSource.DISCORD, "b", Duration.ofSeconds(600));

        assertEquals(soon.id(), updates.pendingRestart().orElseThrow().id());
        assertEquals(soon.id(), updates.cancelPendingRestart("stop").orElseThrow().id());
        assertTrue(updates.pendingRestart().isPresent(), "the later one is still standing");
    }

    // ---------------------------------------------------------------- orphans

    @Test
    void anOrphanedRestartIsHowTheUpdaterLearnsTheRestartWorked() {
        // A RESTART request takes down the container that is running it, every time, by design.
        // So a restart found RUNNING on the next boot is the success signal - reporting it as a
        // failure would mean the one request that always works always looks broken.
        final UpdateRequest restart = updates.submit(UpdateKind.RESTART, UpdateSource.DISCORD, "a", Duration.ZERO);
        updates.claimNext().orElseThrow();

        assertEquals(1, updates.settleOrphans("The redeploy happened", "Killed mid-run"));

        final UpdateRequest read = updates.find(restart.id()).orElseThrow();
        assertEquals(UpdateStatus.DONE, read.status());
        assertEquals("The redeploy happened", read.result());
        assertNotNull(read.finished());

        assertEquals(0, updates.settleOrphans("x", "y"), "and a second start finds nothing to do");
    }

    @Test
    void anOrphanedApplyIsAFailureAndSaysSo() {
        // Everything that is not a restart had no business dying, so it reads as what it was.
        final UpdateRequest apply = updates.submit(UpdateKind.APPLY, UpdateSource.DISCORD, "a", Duration.ZERO);
        updates.claimNext().orElseThrow();

        assertEquals(1, updates.settleOrphans("The redeploy happened", "Killed mid-run"));

        final UpdateRequest read = updates.find(apply.id()).orElseThrow();
        assertEquals(UpdateStatus.FAILED, read.status());
        assertEquals("Killed mid-run", read.result());
    }

    @Test
    void settlingOrphansLeavesPendingWorkAlone() {
        final UpdateRequest waiting = updates.submit(UpdateKind.APPLY, UpdateSource.DISCORD, "a", Duration.ZERO);

        assertEquals(0, updates.settleOrphans("restarted", "failed"));
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
