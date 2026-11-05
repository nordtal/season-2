package eu.nordtal.season.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.inbox.StewardRequest;
import eu.nordtal.season.database.online.OnlineDirectory;
import eu.nordtal.season.database.season.SeasonDirectory;
import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.settings.Database;
import eu.nordtal.season.settings.DatabaseSpec;
import eu.nordtal.season.stewardagent.config.RunSpec;
import eu.nordtal.season.stewardagent.schema.Schema;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the mode switch and the resting of the inactive server against a real database.
 *
 * The containers are stand-ins and the clock is driven. Hunger Games is active and runs, the SMP rests.
 */
class ModeSwitchTest {

    private final FakeContainers containers = new FakeContainers().running("proxy", "limbo", "hunger-games", "smp");
    private final List<RunStage> progress = new ArrayList<>();

    /** Players a test leaves on a server: each driven sleep writes them into the online counts. */
    private Map<String, Integer> staying = Map.of();

    private TestDatabase postgres;
    private Database database;
    private UpdateDirectory directory;
    private SeasonDirectory season;
    private Waiting waiting;
    private Runner runner;

    @BeforeEach
    void open() {
        postgres = TestDatabase.fresh();
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
        waiting = driven();
        season = SeasonDirectory.using(
                database.dataSource(), ((java.time.InstantSource) waiting::now).withZone(ZoneOffset.UTC));
        runner = new Runner(
                defaults(),
                database,
                containers,
                new FakeSnapshots(containers.calls),
                directory,
                waiting,
                PluginRemoval.NONE);
        if (season.state().mode() != SeasonMode.HUNGER_GAMES) {
            season.setMode(SeasonMode.HUNGER_GAMES, Actor.HOST, null);
        }
        containers.stop("smp-container");
        containers.calls.clear();
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void aSwitchStopsTheOldServerWritesTheModeThenStartsTheTarget() {
        final List<String> seen = new ArrayList<>();
        containers.watcher = call -> seen.add(call + " " + season.state().mode());
        final UpdateRequest request = switchTo(SeasonMode.SMP);

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), steps(request.id()).toString());
        assertEquals(
                List.of("stop:hunger-games-container HUNGER_GAMES", "start:smp-container SMP"),
                seen,
                "the mode turns between the stop of the old server and the start of the target");
        assertEquals(SeasonMode.SMP, season.state().mode());
        assertEquals(
                List.of("hunger-games"),
                directory.find(request.id()).orElseThrow().moving(),
                "everyone on a running game server goes to the waiting room");
        assertEquals(
                List.of(
                        "COUNTDOWN DONE",
                        "PLAYERS_MOVED WARNING PLAYERS_UNKNOWN",
                        "STOP hunger-games DONE",
                        "START smp DONE",
                        "VERIFY smp DONE",
                        "PLAYERS_BACK DONE"),
                steps(request.id()));
    }

    /** A failed start keeps the mode on the target and the players waiting, and Try again only brings it up. */
    @Test
    void aFailedSwitchKeepsTheModeAndTryAgainOnlyStartsTheTarget() {
        final UpdateRequest failing = switchTo(SeasonMode.SMP);
        containers.neverHealthy("smp");

        final Outcome failed = runner.run(failing, progress::add);

        assertEquals(UpdateStatus.FAILED, failed.status(), steps(failing.id()).toString());
        assertEquals(SeasonMode.SMP, season.state().mode(), "a retry finds the mode on the target");
        assertTrue(
                steps(failing.id()).contains("VERIFY smp FAILED NOT_HEALTHY"),
                steps(failing.id()).toString());
        directory.finish(failing.id(), failed.stage());

        containers.back("smp");
        containers.calls.clear();
        final UpdateRequest again = switchTo(SeasonMode.SMP);
        final Outcome retried = runner.run(again, progress::add);

        assertEquals(UpdateStatus.DONE, retried.status(), steps(again.id()).toString());
        assertEquals(List.of(), containers.calls, "a target that runs is neither stopped nor started again");
        assertEquals(
                List.of("COUNTDOWN DONE", "PLAYERS_MOVED DONE", "VERIFY smp DONE", "PLAYERS_BACK DONE"),
                steps(again.id()));
    }

    @Test
    void aSwitchToTheActiveModeWithoutAFailedOneDoesNothing() {
        final UpdateRequest request = switchTo(SeasonMode.HUNGER_GAMES);

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(
                RunStage.NOTHING_TO_DO, outcome.stage(), steps(request.id()).toString());
        assertEquals(List.of(), containers.calls);
        assertEquals(null, directory.find(request.id()).orElseThrow().countdownEnd(), "nobody is moved");
    }

    /** Every run stops its servers after the same bounded wait, and the proxy moves those left as one stops. */
    @Test
    void playersWhoStayOnTheOldServerAreWarnedAboutAndTheSwitchGoesOn() {
        staying = Map.of("hunger-games", 2);
        final UpdateRequest request = switchTo(SeasonMode.SMP);

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), steps(request.id()).toString());
        assertEquals(SeasonMode.SMP, season.state().mode());
        assertEquals(
                List.of(
                        "COUNTDOWN DONE",
                        "PLAYERS_MOVED WARNING PLAYERS_STAYED",
                        "STOP hunger-games DONE",
                        "START smp DONE",
                        "VERIFY smp DONE",
                        "PLAYERS_BACK DONE"),
                steps(request.id()));
    }

    @Test
    void theStartStopsTheInactiveServerAndStartsTheActiveOne() {
        containers.back("smp");
        containers.stop("hunger-games-container");
        containers.calls.clear();

        runner.restInactive();

        assertEquals(List.of("stop:smp-container", "start:hunger-games-container"), containers.calls);
    }

    @Test
    void theStartLeavesAnActiveServerThatIsHeldDown() {
        final Outcome down = runner.run(claimed(UpdateKind.DOWN, List.of("hunger-games")), progress::add);
        assertEquals(
                UpdateStatus.DONE, down.status(), steps(directory.latestId()).toString());
        directory.finish(directory.latestId(), down.stage());
        containers.back("smp");
        containers.calls.clear();

        runner.restInactive();

        assertEquals(List.of("stop:smp-container"), containers.calls, "a take-down outlasts the agent's start");
    }

    @Test
    void aRestartLeavesTheRestingServerStopped() {
        final UpdateRequest request = claimed(UpdateKind.RESTART, List.of("hunger-games", "smp"));

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), steps(request.id()).toString());
        assertEquals(List.of("stop:hunger-games-container", "start:hunger-games-container"), containers.calls);
    }

    @Test
    void aRestartOfTheRestingServerAloneDoesNothing() {
        final UpdateRequest request = claimed(UpdateKind.RESTART, List.of("smp"));

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(
                RunStage.NOTHING_TO_DO, outcome.stage(), steps(request.id()).toString());
        assertEquals(List.of(), containers.calls);
        assertEquals(List.of("SCOPE smp SKIPPED NOT_IN_SCOPE"), steps(request.id()));
    }

    @Test
    void aHandStartWakesTheRestingServerAndARestartKeepsItRunning() {
        final Outcome started = runner.run(claimed(UpdateKind.START, List.of("smp")), progress::add);
        assertEquals(
                UpdateStatus.DONE, started.status(), steps(directory.latestId()).toString());
        directory.finish(directory.latestId(), started.stage());
        containers.calls.clear();

        final UpdateRequest restart = claimed(UpdateKind.RESTART, List.of("smp"));
        final Outcome outcome = runner.run(restart, progress::add);

        assertEquals(UpdateStatus.DONE, outcome.status(), steps(restart.id()).toString());
        assertEquals(List.of("stop:smp-container", "start:smp-container"), containers.calls);
    }

    private UpdateRequest switchTo(final SeasonMode target) {
        directory.submit(new StewardRequest.SwitchMode(target, "a test"), List.of(), Actor.HOST, Duration.ZERO);
        return directory.claimNext().orElseThrow();
    }

    private UpdateRequest claimed(final UpdateKind kind, final @Nullable List<String> services) {
        directory.submit(kind, Actor.HOST, Duration.ZERO, services);
        return directory.claimNext().orElseThrow();
    }

    /** The steps of the run as rows: step, subject, outcome and reason. */
    private List<String> steps(final long run) {
        final List<String> steps = new ArrayList<>();
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

    private static RunSpec defaults() {
        return new RunSpec() {

            @Override
            public RunSpec.BackupSpec backup() {
                return new RunSpec.BackupSpec() {

                    @Override
                    public RetentionSpec retention() {
                        return new RetentionSpec() {};
                    }
                };
            }
        };
    }

    /** A clock that moves only when the run sleeps, letting started servers turn healthy and players stay. */
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
                if (!staying.isEmpty()) {
                    OnlineDirectory.using(database.dataSource(), this::now).write(staying);
                }
                return true;
            }
        };
    }
}
