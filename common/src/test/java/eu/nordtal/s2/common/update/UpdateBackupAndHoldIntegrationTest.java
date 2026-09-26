package eu.nordtal.s2.common.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.s2.common.access.AccessSchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Exercises the backup proof and the service holds of {@link UpdateDirectory} against a real PostgreSQL.
 *
 * Skips itself when no Docker daemon is reachable, like every integration test in this module.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UpdateBackupAndHoldIntegrationTest {

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

    @BeforeEach
    void freshInbox() {
        execute("TRUNCATE TABLE service_hold, update_request RESTART IDENTITY");
        updates = UpdateDirectory.using(dataSource);
    }

    /**
     * The one volume line that makes a report a backup, and the one that does not.
     *
     * Built through {@link UpdateReports#toJson} rather than written out as a string, because
     * what is being asserted is that the reader and the writer agree - a literal here would pass
     * for as long as somebody remembered to edit it.
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

    /**
     * A23: every service back, nothing snapshotted, and the row says DONE.
     *
     * This is the shape that made the check necessary. Run 23 reported a successful backup
     * having saved zero volumes, and no surface anywhere drew a difference between that and a night
     * that worked.
     */
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
    void aHoldSurvivesRefreshesRatherThanDuplicatesAndGoesAwayAgain() {
        // Holds outlive a restart of the worker and the interface, so this runs against a real table.
        final UpdateRequest down =
                updates.submit(UpdateKind.DOWN, UpdateSource.CONSOLE, "Till", Duration.ZERO, List.of("smp"));

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
    void aHoldWhoseDownRowIsDeletedKeepsTheHoldAndForgetsTheRow() {
        // ON DELETE SET NULL: a hold that vanished with its row would let the next run start the service.
        final UpdateRequest down =
                updates.submit(UpdateKind.DOWN, UpdateSource.CONSOLE, "Till", Duration.ZERO, List.of("limbo"));
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
                java.sql.ResultSet rows = statement.executeQuery("SELECT max(id) FROM update_request")) {
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
