package eu.nordtal.season.steward.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertBook;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.alert.Measure;
import eu.nordtal.season.database.alert.RaisedAlert;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.steward.AdminPlain;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests that the monitor raises a change of a type, not unchanged numbers, and a failed run once. */
class AlertMonitorTest {

    private static final long GIB = 1L << 30;

    private static DataSource dataSource;

    /** The owner role, for fixtures and for other services. */
    private static DataSource owner;

    private AlertBook book;
    private UpdateDirectory runs;
    private Supplier<StackReading> reading;
    private Thresholds thresholds;
    private AlertMonitor monitor;

    @BeforeAll
    static void startDatabase() {
        final TestDatabase database = TestDatabase.fresh();
        owner = database.dataSource();
        // Connects as the role that Steward uses, so a missing grant fails here.
        dataSource = database.dataSourceAs(DatabaseRole.STEWARD);
    }

    @BeforeEach
    void freshTables() throws java.sql.SQLException {
        try (var connection = owner.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("TRUNCATE journal_entry, steward_inbox CASCADE");
        }
        book = AlertBook.using(dataSource);
        runs = UpdateDirectory.using(dataSource);
        reading = () -> stack(10, "running");
        thresholds = new Thresholds(85, 90, 36);
        monitor = new AlertMonitor(() -> reading.get(), () -> thresholds, book, runs, Clock.systemUTC());
    }

    /** Builds a reading with fresh backups, one service in {@code state} and {@code diskGib} of 100 GiB used. */
    private static StackReading stack(final long diskGib, final String state) {
        final Instant fresh = Instant.now().minusSeconds(60);
        return new StackReading(
                List.of(new StackReading.Service("smp", state, null, StackReading.Purpose.SERVES, false)),
                null,
                List.of(
                        new StackReading.Archive("db-20261002T110000Z.dump", fresh, false, true),
                        new StackReading.Archive("smp-world-20261002T110000Z.tar.zst", fresh, false, true)),
                new StackReading.Host(diskGib * GIB, 100 * GIB, 50 * GIB, 100 * GIB),
                List.of("smp-world"),
                Map.of(),
                null);
    }

    private List<String> raised() {
        return book.claimUnrouted().stream()
                .map(RaisedAlert::alert)
                .map(alert -> alert.type().key() + " " + alert.level().key() + " " + AdminPlain.of(alert.title()))
                .toList();
    }

    @Test
    void aChangedThresholdAppliesAtTheNextReading() {
        reading = () -> stack(80, "running");
        monitor.poll();
        thresholds = new Thresholds(75, 90, 36);
        monitor.poll();
        assertEquals(List.of("disk warn The disk is over its threshold"), raised());
    }

    @Test
    void theFirstReadingIsOnlyTheBaseline() {
        reading = () -> stack(90, "exited");
        monitor.poll();
        assertEquals(List.of(), raised());
        assertEquals(2, monitor.snapshot().alerts().size());
        assertEquals(Alert.Level.DOWN, monitor.snapshot().level());
    }

    @Test
    void aChangeIsRaisedOnceAndItsClearingToo() {
        monitor.poll();
        reading = () -> stack(90, "running");
        monitor.poll();
        assertEquals(List.of("disk warn The disk is over its threshold"), raised());

        // A number that changes within the same verdict raises nothing.
        reading = () -> stack(91, "running");
        monitor.poll();
        assertEquals(List.of(), raised());

        reading = () -> stack(10, "running");
        monitor.poll();
        assertEquals(List.of("disk ok The disk is below its threshold again"), raised());
        assertEquals(Alert.Level.OK, monitor.snapshot().level());
    }

    @Test
    void severalOfOneTypeAreOneAlertNamingThemAll() {
        monitor.poll();
        reading = () -> new StackReading(
                List.of(
                        new StackReading.Service("smp", "exited", null, StackReading.Purpose.SERVES, false),
                        new StackReading.Service("proxy", "exited", null, StackReading.Purpose.SERVES, false)),
                null,
                stack(10, "running").archives(),
                null,
                List.of("smp-world"),
                Map.of(),
                null);
        monitor.poll();
        final List<RaisedAlert> alerts = book.claimUnrouted();
        assertEquals(1, alerts.size());
        assertEquals("smp, proxy", alerts.getFirst().alert().subject());
        assertEquals(
                "Several at once: smp and proxy",
                AdminPlain.of(alerts.getFirst().alert().title()));
        assertEquals(
                List.of("smp: Docker reports the state exited.", "proxy: Docker reports the state exited."),
                AdminPlain.of(alerts.getFirst().alert().lines()));
        assertEquals(
                List.of(
                        Measure.state(Measure.Gauge.CONTAINER_STATE, "smp", "exited"),
                        Measure.state(Measure.Gauge.CONTAINER_STATE, "proxy", "exited")),
                alerts.getFirst().alert().measured(),
                "the one alert keeps what each of its subjects measured");
    }

    @Test
    void anUnreadableStackKeepsWhatWasKnownAndSaysWhy() {
        reading = () -> stack(90, "running");
        monitor.poll();
        reading = () -> {
            throw new IllegalStateException("the agent did not answer");
        };
        monitor.poll();
        assertEquals("the agent did not answer", monitor.snapshot().unreadable());
        assertEquals(1, monitor.snapshot().alerts().size());
        assertEquals(List.of(), raised());

        reading = () -> stack(90, "running");
        monitor.poll();
        assertNull(monitor.snapshot().unreadable());
        assertEquals(List.of(), raised());
    }

    @Test
    void nothingReadYetIsNotGreen() {
        assertNull(monitor.snapshot().checkedAt());
        assertEquals(Alert.Level.WARN, monitor.snapshot().level());
    }

    @Test
    void aFailedRunIsRaisedOnceAndADoneOneNever() {
        final long failed = finished(UpdateKind.UPDATE, UpdateStatus.FAILED, "{}");
        finished(UpdateKind.UPDATE, UpdateStatus.DONE, "{}");

        monitor.runs();
        monitor.runs();

        final List<RaisedAlert> alerts = book.claimUnrouted();
        assertEquals(1, alerts.size());
        final Alert alert = alerts.getFirst().alert();
        assertEquals(AlertType.RUN, alert.type());
        assertEquals("The update failed", AdminPlain.of(alert.title()));
        assertEquals(List.of("Run " + failed + "."), AdminPlain.of(alert.lines()));
        assertEquals("/operations/updates/" + failed, alert.path());
        assertNotNull(alerts.getFirst().raised());
    }

    @Test
    void aFailedRunsAlertNamesItsKindAndItsFailedAndWarnedSteps() throws java.sql.SQLException {
        final long failed = finished(UpdateKind.BACKUP, UpdateStatus.FAILED, "{}");
        try (var connection = owner.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO run_step (run, seq, step, service, finished, outcome, reason)
                    VALUES (%1$d, 1, 'STOP', 'smp', now(), 'DONE', NULL),
                           (%1$d, 2, 'START', 'smp', now(), 'FAILED', 'NOT_HEALTHY'),
                           (%1$d, 3, 'CLEANUP', NULL, now(), 'WARNING', NULL)""".formatted(failed));
        }

        monitor.runs();

        final Alert alert = book.claimUnrouted().getFirst().alert();
        assertEquals("The backup failed", AdminPlain.of(alert.title()));
        assertEquals(
                List.of(
                        "Run " + failed + ".",
                        AdminPlain.of(new JournalDetail.FailedStep(
                                        RunStep.Kind.START, "smp", RunStep.Outcome.FAILED, RunStep.Reason.NOT_HEALTHY)
                                .line()),
                        AdminPlain.of(
                                new JournalDetail.FailedStep(RunStep.Kind.CLEANUP, null, RunStep.Outcome.WARNING, null)
                                        .line())),
                AdminPlain.of(alert.lines()),
                "the run, then its failed and warned steps in order, and nothing that went right");
    }

    private long finished(final UpdateKind kind, final UpdateStatus status, final String report) {
        final long id = runs.submit(kind, Actor.STEWARD, null).id();
        // The part of steward-agent, as the owner role.
        final UpdateDirectory agent = UpdateDirectory.using(owner);
        assertEquals(id, agent.claimNext().orElseThrow().id());
        assertEquals(status, agent.finish(id, status, report).orElseThrow().status());
        return id;
    }
}
