package eu.nordtal.s2.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.inbox.StewardRequest;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.database.update.UpdateKind;
import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateReports;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.database.update.UpdateStatus;
import eu.nordtal.s2.settings.DatabaseSpec;
import eu.nordtal.s2.settings.DatabaseWaiting;
import eu.nordtal.s2.stewardagent.config.RunSpec;
import eu.nordtal.s2.stewardagent.config.RunSpec.BackupSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every kind of run, driven through the one sequence against a real database: the row is what the proxy reads.
 *
 * The containers are stand-ins and the clock is driven, so a thirty-second countdown takes no time.
 */
class RunnerTest {

    private final FakeContainers containers = new FakeContainers().running("smp", "discord-bot", "limbo");
    private final FakeSnapshots snapshots = new FakeSnapshots(containers.calls);
    private final List<UpdateReport> progress = new ArrayList<>();

    /** Removes only what a test added, and writes each removal into the calls beside the stops and starts. */
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
        postgres = TestDatabase.fresh();
        database = DatabaseWaiting.open(
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
        directory = UpdateDirectory.using(database.dataSource());
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

        assertEquals(UpdateStatus.DONE, outcome.status(), outcome.report());
        assertEquals(List.of("stop:smp-container", "start:smp-container"), containers.calls);
        final UpdateRequest row = directory.find(request.id()).orElseThrow();
        assertNotNull(
                row.countdownEnd(), "the proxy warns from this column, so a restart that skips it is unannounced");
        assertEquals(List.of("smp"), row.moving(), "the countdown names what moves, and only that");
        assertTrue(
                progress.stream().anyMatch(report -> report.stage() == UpdateReport.Stage.COUNTDOWN),
                "the feeds see the countdown too: " + progress);
    }

    /** A one-shot's last act is the long-running agent at its own release, after every server is back. */
    @Test
    void aOneShotRenewsTheLongRunningAgentLastOfAll() {
        final UpdateRequest request = claimed(UpdateKind.RESTART, List.of("smp"));

        final Outcome outcome = runner.asOneShot().run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), outcome.report());
        assertEquals(List.of("stop:smp-container", "start:smp-container", "renew:steward-agent"), containers.calls);
        assertEquals(
                UpdateReport.State.HEALTHY,
                UpdateReports.parse(outcome.report())
                        .orElseThrow()
                        .line("steward-agent")
                        .state(),
                outcome.report());
    }

    @Test
    void aBackupDumpsWithEverythingRunningThenSavesTheVolumesWhileTheirServersAreDown() {
        final UpdateRequest request = claimed(UpdateKind.BACKUP, null);

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), outcome.report());
        assertEquals(
                List.of(
                        "dump",
                        "stop:discord-bot-container",
                        "stop:smp-container",
                        "backup:nordtal-s2_mc-smp",
                        "backup:nordtal-s2_mc-smp-plugins",
                        "backup:nordtal-s2_mc-hunger-games-plugins",
                        "backup:nordtal-s2_bot-config",
                        "prune:" + defaults().backup().retention().daily(),
                        "start:discord-bot-container",
                        "start:smp-container"),
                containers.calls);
        assertEquals(
                List.of("discord-bot", "smp"),
                directory.find(request.id()).orElseThrow().moving());
    }

    @Test
    void aTakeDownLeavesTheServerDownUntilAStartReleasesIt() {
        final Outcome down = runner.run(claimed(UpdateKind.DOWN, List.of("smp")), progress::add);

        assertEquals(UpdateStatus.DONE, down.status(), down.report());
        assertEquals(List.of("stop:smp-container"), containers.calls, "a take-down starts nothing again");
        assertTrue(directory.isHeld("smp"));
        directory.finish(directory.running().orElseThrow().id(), UpdateStatus.DONE, down.report());

        containers.calls.clear();
        final UpdateRequest start = claimed(UpdateKind.START, List.of("smp"));
        final Outcome started = runner.run(start, progress::add);

        assertEquals(UpdateStatus.DONE, started.status(), started.report());
        assertEquals(List.of("start:smp-container"), containers.calls);
        assertFalse(directory.isHeld("smp"));
        assertEquals(
                null,
                directory.find(start.id()).orElseThrow().countdownEnd(),
                "starting a server takes nobody off one, so nobody is warned");
    }

    @Test
    void aRunWithNothingToDoStopsNothingAndAnnouncesNothing() {
        directory.hold("smp", Actor.HOST, null);
        final UpdateRequest request = claimed(UpdateKind.RESTART, List.of("smp"));

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), outcome.report());
        assertTrue(outcome.report().contains("NOTHING_TO_DO"), outcome.report());
        assertEquals(List.of(), containers.calls, "a run that discovers there is nothing to do must stop nothing");
        assertEquals(null, directory.find(request.id()).orElseThrow().countdownEnd());
    }

    @Test
    void aRecreateCountsDownStopsTheServerAndMakesItsContainerFromTheImageOnThisHost() {
        final UpdateRequest request = claimed(UpdateKind.RECREATE, List.of("smp"));

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), outcome.report());
        assertEquals(List.of("stop:smp-container", "recreate-local:smp"), containers.calls);
        assertNotNull(directory.find(request.id()).orElseThrow().countdownEnd(), "players on smp are warned first");
    }

    @Test
    void aDeployOfTheBotPullsItsImageWithoutWarningAnyPlayer() {
        final UpdateRequest request = claimed(UpdateKind.DEPLOY, List.of("discord-bot"));

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), outcome.report());
        assertEquals(List.of("stop:discord-bot-container", "recreate:discord-bot"), containers.calls);
        assertEquals(null, directory.find(request.id()).orElseThrow().countdownEnd(), "nobody stands on the bot");
    }

    @Test
    void aRecreateOfPostgresIsAnnouncedAndMadeOnceTheRestIsBack() {
        containers.running("postgres");
        final UpdateRequest request = claimed(UpdateKind.RECREATE, List.of("postgres", "smp"));

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), outcome.report());
        assertEquals(List.of("stop:smp-container", "recreate-local:smp", "recreate-local:postgres"), containers.calls);
        assertEquals(
                List.of("smp", "postgres"),
                directory.find(request.id()).orElseThrow().moving());
    }

    @Test
    void aRecreateOfTheAgentItselfIsRefusedAndStopsNothing() {
        final Outcome outcome = runner.run(claimed(UpdateKind.RECREATE, List.of("steward-agent")), progress::add);

        assertEquals(UpdateStatus.FAILED, outcome.status(), outcome.report());
        assertTrue(outcome.report().contains("one-shot"), outcome.report());
        assertEquals(List.of(), containers.calls);
    }

    @Test
    void aPluginRemovalStopsTheServerRemovesThePluginAndStartsItAgain() {
        final UpdateRequest request = claimed(new StewardRequest.RemovePlugin(List.of("smp"), "chunky"));

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), outcome.report());
        assertEquals(List.of("stop:smp-container", "remove:smp/chunky", "start:smp-container"), containers.calls);
        assertTrue(outcome.report().contains("chunky-1.0.jar"), outcome.report());
    }

    @Test
    void aRemovalOfAPluginNobodyAddedStopsNothing() {
        final Outcome outcome =
                runner.run(claimed(new StewardRequest.RemovePlugin(List.of("smp"), "worldedit")), progress::add);

        assertEquals(UpdateStatus.FAILED, outcome.status(), outcome.report());
        assertEquals(List.of(), containers.calls);
    }

    @Test
    void aVolumeRestoreSavesTheVolumeAsItIsFirstThenPutsTheArchiveBackWhileItsServerIsDown() {
        final UpdateRequest request =
                claimed(new StewardRequest.Restore(List.of(), "nordtal-s2_mc-smp-20260913T000000Z.tar.zst"));

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), outcome.report());
        assertEquals(
                List.of(
                        "stop:smp-container",
                        "backup:nordtal-s2_mc-smp",
                        "restore:nordtal-s2_mc-smp-20260913T000000Z.tar.zst",
                        "start:smp-container"),
                containers.calls);
        assertNotNull(directory.find(request.id()).orElseThrow().countdownEnd(), "a restore is announced");
        assertTrue(outcome.report().contains("restored "), "the line says what was restored: " + outcome.report());
    }

    @Test
    void aVolumeRestoreWhoseFreshBackupFailedRestoresNothing() {
        snapshots.fails("nordtal-s2_mc-smp");

        final Outcome outcome = runner.run(
                claimed(new StewardRequest.Restore(List.of(), "nordtal-s2_mc-smp-20260913T000000Z.tar.zst")),
                progress::add);

        assertEquals(UpdateStatus.FAILED, outcome.status(), outcome.report());
        assertEquals(
                List.of("stop:smp-container", "backup:nordtal-s2_mc-smp", "start:smp-container"), containers.calls);
    }

    @Test
    void aDatabaseRestoreDumpsFirstThenStopsWhatRunsOnTheDatabaseAndCarriesItsOwnRowAcross() {
        final UpdateRequest request = claimed(new StewardRequest.Restore(List.of(), "nordtal-20260913T000000Z.dump"));

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), outcome.report());
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
    }

    @Test
    void aRestoreOfAnArchiveThatIsNotThereStopsNothing() {
        final Outcome outcome =
                runner.run(claimed(new StewardRequest.Restore(List.of(), "nordtal-s2_mc-smp.tar.zst")), progress::add);

        assertEquals(UpdateStatus.FAILED, outcome.status(), outcome.report());
        assertEquals(List.of(), containers.calls);
    }

    /** Submits a request of any kind due now and claims it. */
    private UpdateRequest claimed(final StewardRequest request) {
        directory.submit(request, Actor.HOST, Duration.ZERO);
        return directory.claimNext().orElseThrow();
    }

    /** Submits a run due now and claims it, as the loop does. */
    private UpdateRequest claimed(final UpdateKind kind, final List<String> services) {
        directory.submit(kind, Actor.HOST, Duration.ZERO, services);
        return directory.claimNext().orElseThrow();
    }

    /** A clock that moves only when the run sleeps, and lets every started server pass its healthcheck meanwhile. */
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
