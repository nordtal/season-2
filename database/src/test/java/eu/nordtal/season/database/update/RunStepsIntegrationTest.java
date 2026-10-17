package eu.nordtal.season.database.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.TestDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * A run's steps, its services, its stage and the proxy's player counts, written against a real PostgreSQL.
 *
 * Skips itself when no Docker daemon is reachable, like every integration test in this module.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunStepsIntegrationTest {

    private static DataSource dataSource;

    private UpdateDirectory updates;
    private RunRecorder recorder;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.fresh().dataSource();
    }

    @BeforeEach
    void freshInbox() {
        execute("TRUNCATE TABLE service_hold, steward_inbox RESTART IDENTITY CASCADE");
        updates = UpdateDirectory.using(dataSource);
        recorder = updates.recorder();
    }

    @Test
    void everyValueTheEnumsNameIsOneTheTablesTake() {
        final long run =
                updates.submit(UpdateKind.BACKUP, Actor.HOST, Duration.ZERO).id();
        for (final RunStep.Kind kind : RunStep.Kind.values()) {
            recorder.begin(run, kind, null);
        }
        final long step = recorder.begin(run, RunStep.Kind.INSTALL, "smp");
        for (final RunStep.Image image : RunStep.Image.values()) {
            recorder.change(step, image.name(), null, null, image);
        }
        for (final RunStep.Reason reason : RunStep.Reason.values()) {
            recorder.end(run, recorder.begin(run, RunStep.Kind.SCOPE, null), RunStep.Outcome.FAILED, reason);
        }
        for (final RunStep.Outcome outcome : RunStep.Outcome.values()) {
            recorder.end(run, recorder.begin(run, RunStep.Kind.SCOPE, null), outcome, null);
        }

        assertEquals(
                RunStep.Kind.values().length + 1 + RunStep.Reason.values().length + RunStep.Outcome.values().length,
                number("SELECT count(*) FROM run_step WHERE run = " + run));
        assertEquals(
                List.of(String.valueOf(RunStep.Kind.values().length + 1)),
                strings("SELECT max(seq) FROM run_step WHERE run = " + run + " AND step = 'INSTALL'"));
    }

    @Test
    void aStepEndsOnceAndEndOpenClosesTheRest() {
        final long run =
                updates.submit(UpdateKind.BACKUP, Actor.HOST, Duration.ZERO).id();
        final long saved = recorder.begin(run, RunStep.Kind.SAVE_VOLUME, "nordtal-s2_mc-smp");
        recorder.begin(run, RunStep.Kind.START, "smp");

        recorder.end(run, saved, RunStep.Outcome.DONE, null);
        recorder.end(run, saved, RunStep.Outcome.FAILED, RunStep.Reason.TOOL_FAILED);
        recorder.endOpen(run, RunStep.Outcome.FAILED, RunStep.Reason.UNEXPECTED);

        assertEquals(
                List.of("SAVE_VOLUME DONE null", "START FAILED UNEXPECTED"),
                strings("SELECT step || ' ' || outcome || ' ' || coalesce(reason, 'null') FROM run_step"
                        + " WHERE run = " + run + " ORDER BY seq"));
    }

    @Test
    void theServicesARequestNamesAreRowsAndTheCountdownMarksTheOnesItStops() {
        final long run = updates.submit(UpdateKind.RESTART, Actor.HOST, Duration.ZERO, List.of("smp", "limbo"))
                .id();
        assertEquals(
                List.of("limbo false", "smp false"),
                strings("SELECT service || ' ' || moving FROM run_service WHERE run = " + run + " ORDER BY service"));

        assertEquals(run, updates.claimNext().orElseThrow().id());
        updates.startCountdown(run, Duration.ofSeconds(60), List.of("smp", "hunger-games"));

        assertEquals(
                List.of("hunger-games true", "limbo false", "smp true"),
                strings("SELECT service || ' ' || moving FROM run_service WHERE run = " + run + " ORDER BY service"));

        assertEquals(run, updates.cancelCountdown().orElseThrow().id());
        assertEquals(List.of("CANCELLED"), strings("SELECT stage FROM steward_inbox WHERE id = " + run));
    }

    @Test
    void theStageFollowsTheReportAndAFinishedRunEndsAtItsOwn() {
        final long run =
                updates.submit(UpdateKind.BACKUP, Actor.HOST, Duration.ZERO).id();
        assertEquals(run, updates.claimNext().orElseThrow().id());

        updates.progress(run, UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.BACKING_UP)));
        assertEquals(List.of("BACKING_UP"), strings("SELECT stage FROM steward_inbox WHERE id = " + run));

        updates.finish(run, UpdateStatus.DONE, UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.DONE)));
        assertEquals(List.of("DONE"), strings("SELECT stage FROM steward_inbox WHERE id = " + run));
    }

    @Test
    void theProxyAddsPlayersToTheLatestMoveAndNeverCountsMoreBackThanItMoved() {
        final long run =
                updates.submit(UpdateKind.RESTART, Actor.HOST, Duration.ZERO).id();
        recorder.begin(run, RunStep.Kind.PLAYERS_MOVED, null);
        final long latest = recorder.begin(run, RunStep.Kind.PLAYERS_MOVED, null);

        recorder.moved(run, "smp", "limbo", 3);
        recorder.moved(run, "smp", "limbo", 2);
        recorder.returned("smp", "limbo", 4);
        recorder.returned("smp", "limbo", 4);

        assertEquals(
                List.of(latest + " 5 5"), strings("SELECT step || ' ' || moved || ' ' || returned FROM run_players"));
    }

    @Test
    void factsAreRowsOfTheirStep() {
        final long run =
                updates.submit(UpdateKind.BACKUP, Actor.HOST, Duration.ZERO).id();
        final long room = recorder.begin(run, RunStep.Kind.ROOM_CHECK, null);
        final long cleanup = recorder.begin(run, RunStep.Kind.CLEANUP, null);
        final long copy = recorder.begin(run, RunStep.Kind.OFFSITE_COPY, null);

        recorder.room(room, 900, 400);
        recorder.imageRemoved(cleanup, "sha256:old", 1200);
        recorder.offsite(copy, "4f1c2d", 300);

        assertEquals(List.of("900 400"), strings("SELECT bytes_needed || ' ' || bytes_free FROM run_room"));
        assertEquals(List.of("sha256:old 1200"), strings("SELECT image || ' ' || bytes FROM run_image_removed"));
        assertEquals(List.of("4f1c2d 300"), strings("SELECT snapshot || ' ' || bytes_added FROM run_offsite"));
    }

    private static long number(final String sql) {
        return Long.parseLong(strings(sql).getFirst());
    }

    private static List<String> strings(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            final List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        } catch (final SQLException failure) {
            throw new IllegalStateException(sql, failure);
        }
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
