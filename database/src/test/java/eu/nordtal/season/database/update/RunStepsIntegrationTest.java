package eu.nordtal.season.database.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

/** The steps, services and stage of a run and the player counts of the proxy, written against PostgreSQL. */
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
        execute("TRUNCATE TABLE service_hold, steward_inbox, backup_archive RESTART IDENTITY CASCADE");
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
        final long save = recorder.begin(run, RunStep.Kind.SAVE_VOLUME, "nordtal-s2_mc-smp");
        for (final RunStep.PruneRule rule : RunStep.PruneRule.values()) {
            recorder.archive(save, "nordtal-s2_mc-smp", rule.name(), 1, Duration.ZERO, true);
            recorder.pruned(save, rule.name(), rule);
        }
        for (final RunStep.Outcome outcome : RunStep.Outcome.values()) {
            recorder.end(run, recorder.begin(run, RunStep.Kind.SCOPE, null), outcome, null);
        }

        assertEquals(
                RunStep.Kind.values().length + 2 + RunStep.Reason.values().length + RunStep.Outcome.values().length,
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
    void aRunAheadOfTheSchemaStillWritesItsServicesAndStage() {
        final UpdateDirectory ahead = UpdateDirectory.using(dataSource, () -> false);
        final long run = ahead.submit(UpdateKind.RESTART, Actor.HOST, Duration.ZERO, List.of("smp"))
                .id();
        assertEquals(run, ahead.claimNext().orElseThrow().id());
        ahead.startCountdown(run, Duration.ofSeconds(60), List.of("smp"));
        ahead.progress(run, UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.STOPPING)));

        assertEquals(
                List.of("smp true"),
                strings("SELECT service || ' ' || moving FROM run_service WHERE run = " + run));
        assertEquals(List.of("STOPPING"), strings("SELECT stage FROM steward_inbox WHERE id = " + run));
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

    @Test
    void anArchiveIsARowOfTheStepThatSavedItAndOfTheOneThatPrunedIt() {
        final long run =
                updates.submit(UpdateKind.BACKUP, Actor.HOST, Duration.ZERO).id();
        final long dump = recorder.begin(run, RunStep.Kind.DUMP_DATABASE, null);
        final long save = recorder.begin(run, RunStep.Kind.SAVE_VOLUME, "nordtal-s2_mc-smp");
        recorder.archive(dump, "database", "nordtal-1.dump", 400, Duration.ofMillis(1500), true);
        recorder.archive(save, "nordtal-s2_mc-smp", "nordtal-s2_mc-smp-1.tar.gz", 900, Duration.ofSeconds(40), false);

        final long prune = recorder.begin(run, RunStep.Kind.PRUNE, null);
        recorder.pruned(prune, "nordtal-1.dump", RunStep.PruneRule.RETENTION);
        recorder.pruned(prune, "nordtal-s2_mc-smp-1.tar.gz", RunStep.PruneRule.OVER_BUDGET);
        recorder.pruned(prune, "made-before-the-catalogue.tar.gz", RunStep.PruneRule.RETENTION);
        final long again = recorder.begin(run, RunStep.Kind.PRUNE, null);
        recorder.pruned(again, "nordtal-1.dump", RunStep.PruneRule.OVER_BUDGET);

        assertEquals(
                List.of(
                        new RunStep.Archive("database", "nordtal-1.dump", 400, 1500, true, RunStep.PruneRule.RETENTION),
                        new RunStep.Archive(
                                "nordtal-s2_mc-smp",
                                "nordtal-s2_mc-smp-1.tar.gz",
                                900,
                                40_000,
                                false,
                                RunStep.PruneRule.OVER_BUDGET)),
                updates.recordOf(run).steps().get(2).pruned());
        assertEquals(List.of(), updates.recordOf(run).steps().get(3).pruned());
        assertEquals(
                List.of("nordtal-1.dump"),
                updates.recordOf(run).steps().getFirst().saved().stream()
                        .map(RunStep.Archive::file)
                        .toList());
        assertEquals(2, number("SELECT count(*) FROM backup_archive"));
    }

    @Test
    void aStepThatFailedOnAToolKeepsItsExitCodeAndNoOtherStepHasOne() {
        final long run =
                updates.submit(UpdateKind.BACKUP, Actor.HOST, Duration.ZERO).id();
        final long dump = recorder.begin(run, RunStep.Kind.DUMP_DATABASE, null);
        recorder.end(run, dump, RunStep.Outcome.FAILED, RunStep.Reason.TOOL_FAILED, 2);
        final long save = recorder.begin(run, RunStep.Kind.SAVE_VOLUME, "nordtal-s2_mc-smp");

        assertThrows(
                RuntimeException.class,
                () -> recorder.end(run, save, RunStep.Outcome.FAILED, RunStep.Reason.REFUSED, 1));
        final long offsite = recorder.begin(run, RunStep.Kind.OFFSITE_COPY, null);
        assertThrows(RuntimeException.class, () -> recorder.end(run, offsite, RunStep.Outcome.DONE, null, 0));
        assertEquals(
                List.of(2),
                updates.recordOf(run).steps().stream()
                        .map(RunStep.Row::exitCode)
                        .filter(java.util.Objects::nonNull)
                        .toList());
    }

    @Test
    void theRecordJoinsEveryFactToItsStep() {
        final long run =
                updates.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO).id();
        assertEquals(run, updates.claimNext().orElseThrow().id());
        updates.progress(run, UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.INSTALLING)));
        final long room = recorder.begin(run, RunStep.Kind.ROOM_CHECK, null);
        recorder.begin(run, RunStep.Kind.PLAYERS_MOVED, null);
        final long verify = recorder.begin(run, RunStep.Kind.VERIFY, "smp");
        final long cleanup = recorder.begin(run, RunStep.Kind.CLEANUP, null);
        final long copy = recorder.begin(run, RunStep.Kind.OFFSITE_COPY, null);
        recorder.room(room, 900, 400);
        recorder.moved(run, "smp", "limbo", 3);
        recorder.change(verify, "smp", null, "0.50.0", null);
        recorder.imageRemoved(cleanup, "sha256:old", 1200);
        recorder.cacheFreed(cleanup, 700);
        recorder.offsite(copy, "4f1c2d", 300);
        recorder.end(run, verify, RunStep.Outcome.DONE, null);

        final RunRecord record = updates.recordOf(run);

        assertEquals(UpdateReport.Stage.INSTALLING, record.stage());
        final List<RunStep.Row> steps = record.steps();
        assertEquals(new RunStep.Room(900, 400), steps.get(0).room());
        assertEquals(
                List.of(new RunStep.Move("smp", "limbo", 3, 0)), steps.get(1).players());
        assertEquals(
                List.of(new RunStep.Change("smp", null, "0.50.0", null)),
                steps.get(2).changes());
        assertEquals(RunStep.Outcome.DONE, steps.get(2).outcome());
        assertEquals(
                List.of(new RunStep.ImageRemoved("sha256:old", 1200)),
                steps.get(3).imagesRemoved());
        assertEquals(700L, steps.get(3).cacheFreed());
        assertEquals(new RunStep.Offsite("4f1c2d", 300), steps.get(4).offsite());
        assertEquals(null, steps.get(4).room());
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
