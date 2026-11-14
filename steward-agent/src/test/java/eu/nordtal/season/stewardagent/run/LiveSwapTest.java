package eu.nordtal.season.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.SmpRequest;
import eu.nordtal.season.database.season.SeasonDirectory;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.settings.Database;
import eu.nordtal.season.settings.DatabaseSpec;
import eu.nordtal.season.stewardagent.apply.PackWriter;
import eu.nordtal.season.stewardagent.config.RunSpec;
import eu.nordtal.season.stewardagent.schema.Schema;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Drives a live swap against a real database, with smp as a stand-in that answers or stays silent. */
class LiveSwapTest {

    private static final String URL = "https://pack.nordtal.eu/packs/abc.zip";
    private static final String SHA1 = "6f1ed002ab5595859014ebf0951522d9d0f2ee34";

    private final FakeContainers containers = new FakeContainers().running("proxy", "limbo", "smp");
    private final List<RunStage> progress = new ArrayList<>();

    /** Whether the stand-in smp settles the pack request it finds. */
    private boolean smpAnswers = true;

    private TestDatabase postgres;
    private Database database;
    private UpdateDirectory directory;
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
        final Waiting waiting = driven();
        final SeasonDirectory season = SeasonDirectory.using(
                database.dataSource(), ((java.time.InstantSource) waiting::now).withZone(ZoneOffset.UTC));
        if (season.state().mode() != SeasonMode.SMP) {
            season.setMode(SeasonMode.SMP, Actor.HOST, null);
        }
        runner = new Runner(
                defaults(),
                database,
                containers,
                new FakeSnapshots(containers.calls),
                directory,
                waiting,
                PluginRemoval.NONE);
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void aSwapCountsDownThenHasSmpSendThePackAndStopsNothing() {
        PackWriter.write(SettingStore.using(database.dataSource()), URL, SHA1);
        final UpdateRequest request = swap();

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(RunStage.DONE, outcome.stage(), steps(request.id()).toString());
        assertEquals(List.of("COUNTDOWN DONE", "INSTALL smp DONE"), steps(request.id()));
        assertEquals(List.of(), containers.calls, "a swap stops and starts no container");
    }

    @Test
    void aSwapSmpLeavesUnansweredFails() {
        PackWriter.write(SettingStore.using(database.dataSource()), URL, SHA1);
        smpAnswers = false;
        final UpdateRequest request = swap();

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(RunStage.FAILED, outcome.stage(), steps(request.id()).toString());
        assertEquals(List.of("COUNTDOWN DONE", "INSTALL smp FAILED NOT_HEALTHY"), steps(request.id()));
    }

    @Test
    void aSwapWithoutAPackHasNothingToDo() {
        final UpdateRequest request = swap();

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(
                RunStage.NOTHING_TO_DO, outcome.stage(), steps(request.id()).toString());
        assertEquals(List.of("SCOPE smp SKIPPED UNKNOWN"), steps(request.id()));
    }

    @Test
    void aSwapWhileSmpIsDownHasNothingToDo() {
        PackWriter.write(SettingStore.using(database.dataSource()), URL, SHA1);
        containers.stop("smp-container");
        containers.calls.clear();
        final UpdateRequest request = swap();

        final Outcome outcome = runner.run(request, progress::add);

        assertEquals(
                RunStage.NOTHING_TO_DO, outcome.stage(), steps(request.id()).toString());
        assertEquals(List.of("SCOPE smp SKIPPED NO_CONTAINER"), steps(request.id()));
    }

    private UpdateRequest swap() {
        directory.submit(UpdateKind.LIVE_SWAP, Actor.HOST, Duration.ZERO, null);
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

    /** A clock that moves only when the run sleeps, and a stand-in smp that settles each pack request it sees. */
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
                if (smpAnswers) {
                    final Inbox<SmpRequest> smp = Inbox.over(database.dataSource(), SmpRequest.TABLE);
                    smp.claim()
                            .ifPresent(request ->
                                    smp.settle(request.id(), eu.nordtal.season.database.inbox.Outcome.done(null)));
                }
                return true;
            }
        };
    }
}
