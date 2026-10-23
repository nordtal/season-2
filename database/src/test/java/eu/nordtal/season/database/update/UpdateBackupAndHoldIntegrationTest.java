package eu.nordtal.season.database.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.TestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Exercises the backup proof and the service holds of {@link UpdateDirectory} against PostgreSQL. */
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
        execute("TRUNCATE TABLE service_hold, steward_inbox RESTART IDENTITY CASCADE");
        updates = UpdateDirectory.using(dataSource);
    }

    @Test
    void aRunThatFinishedAndSavedAVolumeIsTheOneItAnswersWith() {
        final long id = backupRow("DONE", 0.25, "DONE");

        final UpdateRequest found =
                updates.lastSuccessfulBackup(Duration.ofHours(12)).orElseThrow();
        assertEquals(id, found.id());
        assertEquals(UpdateKind.BACKUP, found.kind());
    }

    @Test
    void doneWithNothingSavedIsNotABackupTheA23Case() {
        backupRow("DONE", 0.25, null);

        assertTrue(
                updates.lastSuccessfulBackup(Duration.ofHours(12)).isEmpty(),
                "a DONE row that saved no archive was accepted as a backup. That is"
                        + " exactly run 23: every service healthy, every snapshot missing.");
    }

    @Test
    void doneWithEveryVolumeFailedIsNotABackupEither() {
        backupRow("DONE", 0.25, "FAILED");

        assertTrue(
                updates.lastSuccessfulBackup(Duration.ofHours(12)).isEmpty(),
                "the archives are read, not just the status column");
    }

    @Test
    void aFailedRunDoesNotCountEvenWhenItSavedSomethingFirst() {
        // The dump runs before servers stop, so a failed run can hold an archive. Only the status decides.
        backupRow("FAILED", 0.25, "DONE");

        assertTrue(updates.lastSuccessfulBackup(Duration.ofHours(12)).isEmpty());
    }

    @Test
    void yesterdaysBackupIsOutsideAWindowMeasuredInHours() {
        backupRow("DONE", 25.0, "DONE");

        assertTrue(
                updates.lastSuccessfulBackup(Duration.ofHours(12)).isEmpty(),
                "the whole point of the window is that yesterday's backup does not authorise" + " today's reset");
        assertTrue(
                updates.lastSuccessfulBackup(Duration.ofDays(2)).isPresent(),
                "and the row is still there - it is the window that excluded it, not the filter");
    }

    @Test
    void theNewestRowThatCanBeProvedWinsNotSimplyTheNewestRow() {
        final long good = backupRow("DONE", 20.0, "DONE");
        backupRow("DONE", 1.0, null);

        // A LIMIT 1 would answer with the newest row and miss a provable backup further down.
        assertEquals(
                good,
                updates.lastSuccessfulBackup(Duration.ofHours(24)).orElseThrow().id());
    }

    @Test
    void anUpdateIsNotABackupHoweverHealthyItCameBack() {
        execute("INSERT INTO steward_inbox (kind, actor_kind, status, started, finished)"
                + " VALUES ('UPDATE', 'HOST', 'DONE', now(), now())");
        saved(lastId(), "DONE");

        assertTrue(updates.lastSuccessfulBackup(Duration.ofHours(12)).isEmpty());
    }

    /**
     * Writes a settled {@code BACKUP} row {@code hoursAgo} in the past and returns its id.
     *
     * @param save how its one save step ended, {@code null} for none
     */
    private static long backupRow(final String status, final double hoursAgo, final @Nullable String save) {
        final long ago = (long) (hoursAgo * 3600);
        execute("INSERT INTO steward_inbox (kind, actor_kind, status, requested, scheduled_for, started, finished)"
                + " VALUES ('BACKUP', 'STEWARD', '" + status + "',"
                + " now() - make_interval(hours => " + (int) Math.ceil(hoursAgo) + "),"
                + " now() - make_interval(secs => " + ago + "),"
                + " now() - make_interval(secs => " + ago + "),"
                + " now() - make_interval(secs => " + ago + "))");
        final long id = lastId();
        if (save != null) {
            saved(id, save);
        }
        return id;
    }

    /** Writes a save step of the run that ended {@code outcome}, with its archive when it is DONE. */
    private static void saved(final long run, final String outcome) {
        execute("INSERT INTO run_step (run, seq, step, service, finished, outcome) VALUES (" + run
                + ", 1, 'SAVE_VOLUME', 'nordtal-s2_mc-smp', now(), '" + outcome + "')");
        if ("DONE".equals(outcome)) {
            execute("INSERT INTO backup_archive (volume, file, bytes, took, saved_by, stop_verified)"
                    + " SELECT 'nordtal-s2_mc-smp', 'mc-smp-" + run + ".tar.zst', 1, interval '41 seconds', id, true"
                    + " FROM run_step WHERE run = " + run);
        }
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

        // Pressing Down on something already down is no error.
        updates.hold("smp", Actor.person(DiscordId.of("300000000000000002")), down.id());
        assertEquals(1, updates.holds().size(), "the second press wrote a second row");
        assertEquals(
                Actor.person(DiscordId.of("300000000000000002")),
                updates.holds().getFirst().heldBy());

        updates.release("smp");
        assertEquals(List.of(), updates.holds());
        // Releasing twice is no error either.
        updates.release("smp");
    }

    @Test
    void aHoldWhoseDownRowIsDeletedKeepsTheHoldAndForgetsTheRow() {
        // ON DELETE SET NULL: a hold that vanished with its row would let the next run start the service.
        final UpdateRequest down = updates.submit(UpdateKind.DOWN, Actor.HOST, Duration.ZERO, List.of("limbo"));
        updates.hold("limbo", Actor.HOST, down.id());

        execute("DELETE FROM steward_inbox WHERE id = " + down.id());

        final ServiceHold held = updates.holds().getFirst();
        assertEquals("limbo", held.service());
        assertNull(held.requestId(), "the hold went away with the row that explained it");
        updates.release("limbo");
    }

    private static long lastId() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                java.sql.ResultSet rows = statement.executeQuery("SELECT max(id) FROM steward_inbox")) {
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
