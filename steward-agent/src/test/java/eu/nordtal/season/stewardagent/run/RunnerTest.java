package eu.nordtal.season.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.inbox.StewardRequest;
import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.internalapi.agent.SnapshotResult;
import eu.nordtal.season.settings.Database;
import eu.nordtal.season.settings.DatabaseSpec;
import eu.nordtal.season.stewardagent.config.RunSpec;
import eu.nordtal.season.stewardagent.config.RunSpec.BackupSpec;
import eu.nordtal.season.stewardagent.schema.Schema;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives every kind of run through the one sequence against a real database.
 *
 * The containers are stand-ins and the clock is driven.
 */
class RunnerTest {

    private final FakeContainers containers = new FakeContainers().running("smp", "discord-bot", "limbo");
    private final FakeSnapshots snapshots = new FakeSnapshots(containers.calls);
    private final List<RunStage> progress = new ArrayList<>();

    /** Removes only what a test added and records each removal in the calls. */
    private final PluginRemoval removal = new PluginRemoval() {
        @Override
        public boolean has(final String service, final String artifact) {
            return "smp".equals(service) && "chunky".equals(artifact);
        }

        @Override
        public List<String> remove(final String service, final String artifact) {
            containers.calls.add("remove:" + service + "/" + artifact);
            return List.of(artifact + "-1.0.jar");
        }
    };

    private TestDatabase postgres;
    private Database database;
    private UpdateDirectory directory;
    private Runner runner;

    @BeforeEach
    void open() {
        open(TestDatabase.fresh());
    }

    private void open(final TestDatabase over) {
        postgres = over;
        database = Database.open(
                new DatabaseSpec() {
                    @Override
                    public String jdbcUrl() {
                        return postgres.jdbcUrl();
                    }

                    @Override
                    public String username() {
                        return postgres.username();
                    }

                    @Override
                    public String password() {
                        return postgres.password();
                    }
                },
                "steward-test");
        directory = Schema.updates(database);
        runner = new Runner(defaults(), database, containers, snapshots, directory, driven(), removal);
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void aRestartCountsDownOnItsRowThenStopsAndStartsTheServer() {
        final UpdateRequest request = claimed(UpdateKind.RESTART, List.of("smp"));

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), steps().toString());
        assertEquals(List.of("stop:smp-container", "start:smp-container"), containers.calls);
        final UpdateRequest row = directory.find(request.id()).orElseThrow();
        assertNotNull(
                row.countdownEnd(), "the proxy warns from this column, so a restart that skips it is unannounced");
        assertEquals(List.of("smp"), row.moving(), "the countdown names what moves, and only that");
        assertTrue(progress.contains(RunStage.COUNTDOWN), "the feeds see the countdown too: " + progress);
        // The fake runtime counts no players.
        assertEquals(
                List.of(
                        "COUNTDOWN DONE",
                        "PLAYERS_MOVED WARNING PLAYERS_UNKNOWN",
                        "STOP smp DONE",
                        "START smp DONE",
                        "VERIFY smp DONE",
                        "PLAYERS_BACK DONE"),
                steps(request.id()));
    }

    /**
     * A one-shot is a release ahead of the schema until its run migrates, so it writes no table the database lacks.
     *
     * The database is at the newest migration of 0.55.0, the release before the rows of a request.
     */
    @Test
    void aRunOnTheSchemaBeforeItsOwnMigrationWritesNoTableThatIsNotThereYet() {
        database.close();
        final TestDatabase older = TestDatabase.empty();
        older.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "88");
        open(older);

        // The previous release asked for the run, as JSON in the payload.
        try (var connection = older.dataSource().getConnection();
                var statement = connection.createStatement()) {
            statement.execute("INSERT INTO steward_inbox (kind, actor_kind, payload)"
                    + " VALUES ('RESTART', 'HOST', '{\"services\": [\"smp\"]}')");
        } catch (final java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        final UpdateRequest first = directory.claimNext().orElseThrow();
        final Outcome before = runner.run(first, progress::add);

        assertEquals(UpdateStatus.DONE, before.status(), steps().toString());
        directory.finish(first.id(), before.stage());
        older.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "latest");
        final UpdateRequest after = claimed(UpdateKind.RESTART, List.of("smp"));
        assertEquals(UpdateStatus.DONE, runner.run(after, progress::add).status());
        assertFalse(steps(after.id()).isEmpty(), "once the schema has the tables, the run writes its rows again");
    }

    /** A one-shot's last act is the long-running agent at its own release, after every server is back. */
    @Test
    void aOneShotRenewsTheLongRunningAgentLastOfAll() {
        final UpdateRequest request = claimed(UpdateKind.RESTART, List.of("smp"));

        final Outcome outcome = runner.asOneShot().run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), steps().toString());
        assertEquals(List.of("stop:smp-container", "start:smp-container", "renew:steward-agent"), containers.calls);
        final List<String> steps = steps(request.id());
        assertEquals(
                List.of("INSTALL steward-agent DONE", "VERIFY steward-agent DONE"),
                steps.subList(steps.size() - 2, steps.size()),
                "the renewed agent is installed and seen healthy last: " + steps);
    }

    @Test
    void aBackupDumpsWithEverythingRunningThenSavesTheVolumesWhileTheirServersAreDown() {
        final UpdateRequest request = claimed(UpdateKind.BACKUP, null);

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), steps().toString());
        assertEquals(
                List.of(
                        "prune:" + defaults().backup().retention().daily(),
                        "room:4",
                        "dump",
                        "stop:smp-container",
                        "backup:nordtal-s2_mc-smp",
                        "backup:nordtal-s2_mc-smp-plugins",
                        "backup:nordtal-s2_mc-hunger-games",
                        "backup:nordtal-s2_mc-hunger-games-plugins",
                        "prune:" + defaults().backup().retention().daily(),
                        "start:smp-container"),
                containers.calls);
        assertEquals(List.of("smp"), directory.find(request.id()).orElseThrow().moving());
        assertTrue(
                steps(request.id()).contains("DUMP_DATABASE DONE"),
                "a dump acts on no service: " + steps(request.id()));
        final List<String> steps = steps(request.id());
        assertTrue(steps.contains("SAVE_VOLUME nordtal-s2_mc-smp DONE"), steps.toString());
        assertTrue(steps.contains("OFFSITE_COPY SKIPPED NO_REPOSITORY"), "the archives stay on this disk: " + steps);
        assertTrue(progress.contains(RunStage.BACKING_UP), "a backup says so while it saves: " + progress);
    }

    @Test
    void aBackupThatWouldNotLeaveTheDiskItsFreeShareFailsBeforeAnythingStops() {
        snapshots.full();

        final UpdateRequest request = claimed(UpdateKind.BACKUP, null);
        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.FAILED, outcome.status(), steps().toString());
        assertEquals(List.of("prune:" + defaults().backup().retention().daily(), "room:4"), containers.calls);
        assertEquals(List.of("PRUNE DONE", "ROOM_CHECK FAILED NO_ROOM"), steps(request.id()));
    }

    @Test
    void aBackupStopsTheHungerGamesWhileItRunsAndLeavesItDownWhenItIsDown() {
        containers.running("hunger-games");

        final Outcome outcome = runner.run(claimed(UpdateKind.BACKUP, null), progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), steps().toString());
        final List<String> stopsAndStarts = containers.calls.stream()
                .filter(call -> call.startsWith("stop:") || call.startsWith("start:"))
                .toList();
        assertEquals(
                List.of(
                        "stop:hunger-games-container",
                        "stop:smp-container",
                        "start:hunger-games-container",
                        "start:smp-container"),
                stopsAndStarts);
    }

    @Test
    void aBackupCopiesItsArchivesOffTheHostOnlyOnceItsServersAreBack() {
        snapshots.copiesOffsite(SnapshotResult.saved(
                Snapshots.OFFSITE, 4_321_000_000L, Duration.ofSeconds(95), "sftp://box/nordtal-s2"));
        final UpdateRequest request = claimed(UpdateKind.BACKUP, null);

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), steps().toString());
        final int started = containers.calls.indexOf("start:smp-container");
        final int copied = containers.calls.indexOf(
                "offsite:" + defaults().backup().retention().daily());
        assertTrue(started >= 0 && copied > started, "the upload must not keep the servers down: " + containers.calls);
        assertTrue(
                steps(request.id()).contains("OFFSITE_COPY DONE"),
                steps(request.id()).toString());
    }

    @Test
    void aFailedCopyOffTheHostFailsTheBackupAndLeavesTheServersRunning() {
        snapshots.copiesOffsite(
                SnapshotResult.failed(Snapshots.OFFSITE, Duration.ofSeconds(2), "the copy failed: exit [1]"));
        final UpdateRequest request = claimed(UpdateKind.BACKUP, null);

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.FAILED, outcome.status(), steps().toString());
        assertEquals(
                "start:smp-container", containers.calls.get(containers.calls.size() - 2), containers.calls.toString());
        assertTrue(
                steps(request.id()).contains("OFFSITE_COPY FAILED TOOL_FAILED"),
                steps(request.id()).toString());
    }

    @Test
    void aTakeDownLeavesTheServerDownUntilAStartReleasesIt() {
        final Outcome down = runner.run(claimed(UpdateKind.DOWN, List.of("smp")), progress::add);

        assertEquals(UpdateStatus.DONE, down.status(), steps().toString());
        assertEquals(List.of("stop:smp-container"), containers.calls, "a take-down starts nothing again");
        assertTrue(directory.isHeld("smp"));
        final long downId = directory.running().orElseThrow().id();
        assertTrue(steps(downId).contains("HOLD smp DONE"), steps(downId).toString());
        directory.finish(downId, down.stage());

        containers.calls.clear();
        final UpdateRequest start = claimed(UpdateKind.START, List.of("smp"));
        final Outcome started = runner.run(start, progress::add);

        assertEquals(UpdateStatus.DONE, started.status(), steps().toString());
        assertEquals(List.of("start:smp-container"), containers.calls);
        assertFalse(directory.isHeld("smp"));
        assertEquals(
                null,
                directory.find(start.id()).orElseThrow().countdownEnd(),
                "starting a server takes nobody off one, so nobody is warned");
        assertTrue(
                steps(start.id()).contains("UNHOLD smp DONE"), steps(start.id()).toString());
    }

    @Test
    void aRunWithNothingToDoStopsNothingAndAnnouncesNothing() {
        directory.hold("smp", Actor.HOST, null);
        final UpdateRequest request = claimed(UpdateKind.RESTART, List.of("smp"));

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), steps().toString());
        assertEquals(
                RunStage.NOTHING_TO_DO, outcome.stage(), steps(request.id()).toString());
        assertEquals(List.of(), containers.calls, "a run that discovers there is nothing to do must stop nothing");
        assertEquals(null, directory.find(request.id()).orElseThrow().countdownEnd());
    }

    @Test
    void aRecreateCountsDownStopsTheServerAndMakesItsContainerFromTheImageOnThisHost() {
        final UpdateRequest request = claimed(UpdateKind.RECREATE, List.of("smp"));

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), steps().toString());
        assertEquals(List.of("stop:smp-container", "recreate-local:smp"), containers.calls);
        assertNotNull(directory.find(request.id()).orElseThrow().countdownEnd(), "players on smp are warned first");
    }

    @Test
    void aDeployOfTheBotPullsItsImageWithoutWarningAnyPlayer() {
        final UpdateRequest request = claimed(UpdateKind.DEPLOY, List.of("discord-bot"));

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), steps().toString());
        assertEquals(List.of("stop:discord-bot-container", "recreate:discord-bot"), containers.calls);
        assertEquals(null, directory.find(request.id()).orElseThrow().countdownEnd(), "nobody stands on the bot");
    }

    @Test
    void aRecreateOfPostgresIsAnnouncedAndMadeOnceTheRestIsBack() {
        containers.running("postgres");
        final UpdateRequest request = claimed(UpdateKind.RECREATE, List.of("postgres", "smp"));

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), steps().toString());
        assertEquals(List.of("stop:smp-container", "recreate-local:smp", "recreate-local:postgres"), containers.calls);
        assertEquals(
                List.of("postgres", "smp"),
                directory.find(request.id()).orElseThrow().moving());
    }

    /** Caddy carries the standby's port too, so it goes only once the standby has handed its players back. */
    @Test
    void aRunRenewsCaddyOnlyOnceTheProxysStandbyIsClosed() {
        containers.running("proxy", "caddy");
        final UpdateRequest request = claimed(UpdateKind.RECREATE, List.of("caddy", "proxy"));

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), steps().toString());
        final List<String> calls = containers.calls;
        final int standbyStopped = calls.stream()
                .filter(call -> call.startsWith("stop:proxy-standby-container"))
                .findFirst()
                .map(calls::indexOf)
                .orElseThrow(() -> new AssertionError("the standby was never stopped: " + calls));
        assertTrue(
                standbyStopped < calls.indexOf("recreate-local:caddy"),
                "caddy went while players were parked behind it: " + calls);
    }

    @Test
    void aRecreateOfTheAgentItselfIsRefusedAndStopsNothing() {
        final Outcome outcome = runner.run(claimed(UpdateKind.RECREATE, List.of("steward-agent")), progress::add);

        assertEquals(UpdateStatus.FAILED, outcome.status(), steps().toString());
        assertEquals(List.of(), containers.calls);
    }

    @Test
    void aPluginRemovalStopsTheServerRemovesThePluginAndStartsItAgain() {
        final UpdateRequest request = claimed(new StewardRequest.RemovePlugin("chunky"), List.of("smp"));

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), steps().toString());
        assertEquals(List.of("stop:smp-container", "remove:smp/chunky", "start:smp-container"), containers.calls);
        assertEquals(
                List.of(new eu.nordtal.season.database.update.RunStep.Change("chunky", null, null, null)),
                directory.recordOf(request.id()).steps().stream()
                        .filter(step -> step.step() == eu.nordtal.season.database.update.RunStep.Kind.INSTALL)
                        .flatMap(step -> step.changes().stream())
                        .toList(),
                "the step names the plugin it removed: " + steps(request.id()));
    }

    @Test
    void aRemovalOfAPluginNobodyAddedStopsNothing() {
        final Outcome outcome =
                runner.run(claimed(new StewardRequest.RemovePlugin("worldedit"), List.of("smp")), progress::add);

        assertEquals(UpdateStatus.FAILED, outcome.status(), steps().toString());
        assertEquals(List.of(), containers.calls);
    }

    @Test
    void aVolumeRestoreSavesTheVolumeAsItIsFirstThenPutsTheArchiveBackWhileItsServerIsDown() {
        final UpdateRequest request =
                claimed(new StewardRequest.Restore("nordtal-s2_mc-smp-20260913T000000Z.tar.zst"), List.of());

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), steps().toString());
        assertEquals(
                List.of(
                        "room:1",
                        "stop:smp-container",
                        "backup:nordtal-s2_mc-smp",
                        "restore:nordtal-s2_mc-smp-20260913T000000Z.tar.zst",
                        "start:smp-container"),
                containers.calls);
        assertNotNull(directory.find(request.id()).orElseThrow().countdownEnd(), "a restore is announced");
        assertTrue(
                steps(request.id()).contains("RESTORE nordtal-s2_mc-smp-20260913T000000Z.tar.zst DONE"),
                "an archive put back is restored, not updated: " + steps(request.id()));
    }

    @Test
    void aVolumeRestoreWhoseFreshBackupFailedRestoresNothing() {
        snapshots.fails("nordtal-s2_mc-smp");

        final Outcome outcome = runner.run(
                claimed(new StewardRequest.Restore("nordtal-s2_mc-smp-20260913T000000Z.tar.zst"), List.of()),
                progress::add);

        assertEquals(UpdateStatus.FAILED, outcome.status(), steps().toString());
        assertEquals(
                List.of("room:1", "stop:smp-container", "backup:nordtal-s2_mc-smp", "start:smp-container"),
                containers.calls);
    }

    /**
     * Paper started on a half-emptied volume writes defaults over the gaps and may generate a new world.
     */
    @Test
    void aVolumeRestoreThatFailsAfterEmptyingTheVolumeLeavesItsServerDownAndHeld() {
        snapshots.fails("restore");
        final UpdateRequest request =
                claimed(new StewardRequest.Restore("nordtal-s2_mc-smp-20260901T000000Z.tar.zst"), List.of());

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.FAILED, outcome.status(), steps().toString());
        assertEquals(
                List.of(
                        "room:1",
                        "stop:smp-container",
                        "backup:nordtal-s2_mc-smp",
                        "restore:nordtal-s2_mc-smp-20260901T000000Z.tar.zst"),
                containers.calls,
                "nothing starts on what the failed restore left");
        assertTrue(directory.isHeld("smp"), "so no later run starts it either");
        final List<String> steps = steps(request.id());
        assertTrue(
                steps.contains("RESTORE nordtal-s2_mc-smp-20260901T000000Z.tar.zst FAILED LEFT_DOWN"),
                steps.toString());
        assertTrue(steps.contains("HOLD smp DONE"), steps.toString());
        assertTrue(
                steps.contains("SAVE_VOLUME nordtal-s2_mc-smp DONE"),
                "the archive saved first is the way back: " + steps);
    }

    @Test
    void aVolumeRestoreThatFailsBeforeTouchingTheVolumeStartsItsServerAgain() {
        snapshots.fails("unreadable");

        final Outcome outcome = runner.run(
                claimed(new StewardRequest.Restore("nordtal-s2_mc-smp-20260901T000000Z.tar.zst"), List.of()),
                progress::add);

        assertEquals(UpdateStatus.FAILED, outcome.status(), steps().toString());
        assertEquals(
                List.of(
                        "room:1",
                        "stop:smp-container",
                        "backup:nordtal-s2_mc-smp",
                        "restore:nordtal-s2_mc-smp-20260901T000000Z.tar.zst",
                        "start:smp-container"),
                containers.calls);
        assertFalse(directory.isHeld("smp"));
    }

    @Test
    void aDatabaseRestoreDumpsFirstThenStopsWhatRunsOnTheDatabaseAndCarriesItsOwnRowAcross() {
        final UpdateRequest request = claimed(new StewardRequest.Restore("nordtal-20260913T000000Z.dump"), List.of());

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), steps().toString());
        assertEquals(
                List.of(
                        "dump",
                        "stop:limbo-container",
                        "stop:smp-container",
                        "stop:discord-bot-container",
                        "restore-database:nordtal-20260913T000000Z.dump",
                        "start:limbo-container",
                        "start:smp-container",
                        "start:discord-bot-container"),
                containers.calls.stream()
                        .filter(call -> !call.contains("standby"))
                        .toList());
        assertEquals(
                UpdateStatus.RUNNING, directory.find(request.id()).orElseThrow().status());
        final List<String> steps = steps(request.id());
        // The steps before it were rows of the database it replaced; the agent's log keeps them.
        assertEquals("RESTORE nordtal-20260913T000000Z.dump DONE", steps.getFirst(), steps.toString());
    }

    @Test
    void aRestoreOfAnArchiveThatIsNotThereStopsNothing() {
        final Outcome outcome =
                runner.run(claimed(new StewardRequest.Restore("nordtal-s2_mc-smp.tar.zst"), List.of()), progress::add);

        assertEquals(UpdateStatus.FAILED, outcome.status(), steps().toString());
        assertEquals(List.of(), containers.calls);
    }

    /** The steps of the newest run, for a failure message. */
    private List<String> steps() {
        return steps(directory.latestId());
    }

    /** The steps of the run as rows: step, subject, outcome and reason. */
    private List<String> steps(final long run) {
        final List<String> steps = new java.util.ArrayList<>();
        try (java.sql.Connection connection = database.dataSource().getConnection();
                java.sql.PreparedStatement query = connection.prepareStatement(
                        "SELECT concat_ws(' ', step, service, outcome, reason) FROM run_step WHERE run = ? ORDER BY seq")) {
            query.setLong(1, run);
            try (java.sql.ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    steps.add(rows.getString(1));
                }
            }
        } catch (final java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
        }
        return steps;
    }

    /** Submits a request due now and claims it. */
    private UpdateRequest claimed(final StewardRequest request, final List<String> services) {
        directory.submit(request, services, Actor.HOST, Duration.ZERO);
        return directory.claimNext().orElseThrow();
    }

    private UpdateRequest claimed(
            final UpdateKind kind, final @org.jspecify.annotations.Nullable List<String> services) {
        directory.submit(kind, Actor.HOST, Duration.ZERO, services);
        return directory.claimNext().orElseThrow();
    }

    /**
     * A clock that moves only when the run sleeps and lets every started server pass its healthcheck meanwhile.
     */
    private Waiting driven() {
        return new Waiting() {
            private Instant now = Instant.now();

            @Override
            public Instant now() {
                return now;
            }

            @Override
            public boolean sleep(final Duration duration) {
                now = now.plus(duration);
                containers.settle();
                return true;
            }
        };
    }

    private static RunSpec defaults() {
        return new RunSpec() {

            @Override
            public BackupSpec backup() {
                return new BackupSpec() {

                    @Override
                    public RetentionSpec retention() {
                        return new RetentionSpec() {};
                    }
                };
            }
        };
    }
}
