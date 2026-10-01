package eu.nordtal.s2.database.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.TestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Exercises the backup proof and the service holds of {@link UpdateDirectory} against a real PostgreSQL.
 *
 * Skips itself when no Docker daemon is reachable, like every integration test in this module.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UpdateBackupAndHoldIntegrationTest {
    private static DataSource dataSource;

    private UpdateDirectory updates;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void freshInbox() {
        execute("TRUNCATE TABLE service_hold, worker_inbox RESTART IDENTITY");
        updates = UpdateDirectory.using(dataSource);
    }

    /**
     * The one volume line that makes a report a backup, built through {@link UpdateReports#toJson} so both sides agree.
     */
    private static UpdateReport report(final UpdateReport.State volume) {
        return UpdateReport.at(UpdateReport.Stage.DONE)
                .with(new UpdateReport.ServiceLine(
                        "nordtal-s2_mc-smp",
                        volume,
                        List.of(new UpdateReport.Change("backup", null, "1.2 GiB in 41s")),
                        null))
                .with(new UpdateReport.ServiceLine("smp", UpdateReport.State.HEALTHY, List.of(), null));
    }

    /** A run with every service back, nothing snapshotted, and the row DONE, which is no backup. */
    private static UpdateReport reportWithNoVolumes() {
        return UpdateReport.at(UpdateReport.Stage.DONE)
                .with(new UpdateReport.ServiceLine("smp", UpdateReport.State.HEALTHY, List.of(), null));
    }

    @Test
    void aRunThatFinishedAndSavedAVolumeIsTheOneItAnswersWith() {
        final long id = backupRow("DONE", 0.25, UpdateReports.toJson(report(UpdateReport.State.SAVED)));

        final UpdateRequest found =
                updates.lastSuccessfulBackup(Duration.ofHours(12)).orElseThrow();
        assertEquals(id, found.id());
        assertEquals(UpdateKind.BACKUP, found.kind());
    }

    @Test
    void doneWithNothingSavedIsNotABackupTheA23Case() {
        backupRow("DONE", 0.25, UpdateReports.toJson(reportWithNoVolumes()));

        assertTrue(
                updates.lastSuccessfulBackup(Duration.ofHours(12)).isEmpty(),
                "a DONE row whose report lists no saved volume was accepted as a backup. That is"
                        + " exactly run 23: every service healthy, every snapshot missing.");
    }

    @Test
    void doneWithEveryVolumeFailedIsNotABackupEither() {
        backupRow("DONE", 0.25, UpdateReports.toJson(report(UpdateReport.State.FAILED)));

        assertTrue(
                updates.lastSuccessfulBackup(Duration.ofHours(12)).isEmpty(),
                "the report is read, not just the status column");
    }

    @Test
    void aFailedRunDoesNotCountEvenWhenItsReportSavedSomethingFirst() {
        // The dump runs before servers stop, so a failed run can carry SAVED; only the status decides.
        backupRow("FAILED", 0.25, UpdateReports.toJson(report(UpdateReport.State.SAVED)));

        assertTrue(updates.lastSuccessfulBackup(Duration.ofHours(12)).isEmpty());
    }

    @Test
    void yesterdaysBackupIsOutsideAWindowMeasuredInHours() {
        backupRow("DONE", 25.0, UpdateReports.toJson(report(UpdateReport.State.SAVED)));

        assertTrue(
                updates.lastSuccessfulBackup(Duration.ofHours(12)).isEmpty(),
                "the whole point of the window is that yesterday's backup does not authorise" + " today's reset");
        assertTrue(
                updates.lastSuccessfulBackup(Duration.ofDays(2)).isPresent(),
                "and the row is still there - it is the window that excluded it, not the filter");
    }

    @Test
    void aResultNobodyCanParseProvesNothing() {
        // A result that is prose, not a report, cannot tell a saved volume from a sentence.
        backupRow("DONE", 0.25, "Update finished. smp: running");

        assertTrue(updates.lastSuccessfulBackup(Duration.ofHours(12)).isEmpty());
    }

    @Test
    void theNewestRowThatCanBeProvedWinsNotSimplyTheNewestRow() {
        final long good = backupRow("DONE", 20.0, UpdateReports.toJson(report(UpdateReport.State.SAVED)));
        backupRow("DONE", 1.0, UpdateReports.toJson(reportWithNoVolumes()));

        // A LIMIT 1 would answer with the newest row and miss a provable backup further down the window.
        assertEquals(
                good,
                updates.lastSuccessfulBackup(Duration.ofHours(24)).orElseThrow().id());
    }

    @Test
    void anUpdateIsNotABackupHoweverHealthyItCameBack() {
        execute("INSERT INTO worker_inbox (kind, payload, actor_kind, status, started, finished, outcome)"
                + " VALUES ('UPDATE', '{\"services\": []}', 'HOST', 'DONE', now(), now(), $json$"
                + UpdateReports.toJson(report(UpdateReport.State.SAVED)) + "$json$)");

        assertTrue(updates.lastSuccessfulBackup(Duration.ofHours(12)).isEmpty());
    }

    /**
     * Writes a settled {@code BACKUP} row {@code hoursAgo} in the past, on the database's clock.
     *
     * @return the id, so a test can say which row it expected back
     */
    private static long backupRow(final String status, final double hoursAgo, final String result) {
        execute("INSERT INTO worker_inbox (kind, payload, actor_kind, status, requested, scheduled_for, started,"
                + " finished, outcome) VALUES ('BACKUP', '{\"services\": []}', 'STEWARD', '" + status + "',"
                + " now() - make_interval(hours => " + (int) Math.ceil(hoursAgo) + "),"
                + " now() - make_interval(secs => " + (long) (hoursAgo * 3600) + "),"
                + " now() - make_interval(secs => " + (long) (hoursAgo * 3600) + "),"
                + " now() - make_interval(secs => " + (long) (hoursAgo * 3600) + "),"
                // A report is stored as an object, anything else as a JSON string, as steward stores them.
                + (result.startsWith("{")
                        ? " cast($json$" + result + "$json$ AS jsonb))"
                        : " to_jsonb(cast($json$" + result + "$json$ AS text)))"));
        return lastId();
    }

    @Test
    void aHoldSurvivesRefreshesRatherThanDuplicatesAndGoesAwayAgain() {
        // Holds outlive a restart of steward and the interface, so this runs against a real table.
        final UpdateRequest down = updates.submit(UpdateKind.DOWN, Actor.HOST, Duration.ZERO, List.of("smp"));

        updates.hold("smp", Actor.person(DiscordId.of("300000000000000001")), down.id());
        assertTrue(updates.isHeld("smp"));
        assertFalse(updates.isHeld("limbo"), "holding one service held another");

        final ServiceHold held = updates.holds().getFirst();
        assertEquals("smp", held.service());
        assertEquals(Actor.person(DiscordId.of("300000000000000001")), held.heldBy());
        assertEquals(down.id(), held.requestId());
        assertNotNull(held.since());

        // Pressing Down on something that is already down is somebody making sure, not an error.
        updates.hold("smp", Actor.person(DiscordId.of("300000000000000002")), down.id());
        assertEquals(1, updates.holds().size(), "the second press wrote a second row");
        assertEquals(
                Actor.person(DiscordId.of("300000000000000002")),
                updates.holds().getFirst().heldBy());

        updates.release("smp");
        assertEquals(List.of(), updates.holds());
        // Releasing twice is not an error either: the button is idempotent on purpose.
        updates.release("smp");
    }

    @Test
    void aHoldWhoseDownRowIsDeletedKeepsTheHoldAndForgetsTheRow() {
        // ON DELETE SET NULL: a hold that vanished with its row would let the next run start the service.
        final UpdateRequest down = updates.submit(UpdateKind.DOWN, Actor.HOST, Duration.ZERO, List.of("limbo"));
        updates.hold("limbo", Actor.HOST, down.id());

        execute("DELETE FROM worker_inbox WHERE id = " + down.id());

        final ServiceHold held = updates.holds().getFirst();
        assertEquals("limbo", held.service());
        assertNull(held.requestId(), "the hold went away with the row that explained it");
        updates.release("limbo");
    }

    private static long lastId() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                java.sql.ResultSet rows = statement.executeQuery("SELECT max(id) FROM worker_inbox")) {
            rows.next();
            return rows.getLong(1);
        } catch (final SQLException failure) {
            throw new IllegalStateException(failure);
        }
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
