package eu.nordtal.s2.steward.serve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.database.update.UpdateKind;
import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.database.update.UpdateStatus;
import eu.nordtal.s2.settings.DatabaseSpec;
import eu.nordtal.s2.steward.config.BackupSpec;
import eu.nordtal.s2.steward.config.StewardSpec;
import eu.nordtal.s2.steward.schema.Schema;
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

    private TestDatabase postgres;
    private Database database;
    private UpdateDirectory directory;
    private Runner runner;

    @BeforeEach
    void open() {
        postgres = TestDatabase.fresh();
        database = Schema.open(new DatabaseSpec() {
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
        });
        directory = UpdateDirectory.using(database.dataSource());
        runner = new Runner(defaults(), database, containers, snapshots, directory, driven());
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

    @Test
    void aBackupDumpsWithEverythingRunningThenSavesTheVolumesWhileTheirServersAreDown() {
        final UpdateRequest request = claimed(UpdateKind.BACKUP, null);

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), outcome.report());
        assertEquals(
                List.of(
                        "dump",
                        "stop:smp-container",
                        "stop:discord-bot-container",
                        "backup:nordtal-s2_mc-smp",
                        "backup:nordtal-s2_mc-smp-plugins",
                        "backup:nordtal-s2_mc-hunger-games-plugins",
                        "backup:nordtal-s2_bot-config",
                        "prune:" + defaults().backup().retention().daily(),
                        "start:smp-container",
                        "start:discord-bot-container"),
                containers.calls);
        assertEquals(
                List.of("smp", "discord-bot"),
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

    private static StewardSpec defaults() {
        return new StewardSpec() {
            @Override
            public BunqSpec bunq() {
                return new BunqSpec() {};
            }

            @Override
            public UpdateSpec update() {
                return new UpdateSpec() {};
            }

            @Override
            public BackupSpec backup() {
                return new BackupSpec() {
                    @Override
                    public RemoteSpec remote() {
                        return new RemoteSpec() {};
                    }

                    @Override
                    public RetentionSpec retention() {
                        return new RetentionSpec() {};
                    }
                };
            }

            @Override
            public AgentSpec agent() {
                return new AgentSpec() {};
            }
        };
    }
}
