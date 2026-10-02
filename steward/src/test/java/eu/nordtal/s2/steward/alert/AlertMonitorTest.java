package eu.nordtal.s2.steward.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.database.DatabaseRole;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.alert.Alert;
import eu.nordtal.s2.database.alert.AlertBook;
import eu.nordtal.s2.database.alert.AlertType;
import eu.nordtal.s2.database.alert.RaisedAlert;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.database.update.UpdateKind;
import eu.nordtal.s2.database.update.UpdateStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What the monitor raises: a change of a type, never its unchanged numbers, and a failed run exactly once. */
class AlertMonitorTest {

    private static final long GIB = 1L << 30;

    private static DataSource dataSource;

    /** The owner, for fixtures and for whatever stands in for another service. */
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
        // The role steward logs in as, so a statement it was never granted fails here first.
        dataSource = database.dataSourceAs(DatabaseRole.STEWARD);
    }

    @BeforeEach
    void freshTables() throws java.sql.SQLException {
        try (var connection = owner.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("TRUNCATE admin_alert, steward_inbox CASCADE");
        }
        book = AlertBook.using(dataSource);
        runs = UpdateDirectory.using(dataSource);
        reading = () -> stack(10, "running");
        thresholds = new Thresholds(85, 90, 36);
        monitor = new AlertMonitor(() -> reading.get(), () -> thresholds, book, runs, Clock.systemUTC());
    }

    /** A stack with fresh backups, one service in {@code state} and the disk {@code diskGib} of 100 GiB full. */
    private static StackReading stack(final long diskGib, final String state) {
        final Instant fresh = Instant.now().minusSeconds(60);
        return new StackReading(
                List.of(new StackReading.Service("smp", state, null, false, false)),
                null,
                List.of(
                        new StackReading.Archive("db-20261002T110000Z.dump", fresh, false),
                        new StackReading.Archive("smp-world-20261002T110000Z.tar.zst", fresh, false)),
                new StackReading.Host(diskGib * GIB, 100 * GIB, 50 * GIB, 100 * GIB));
    }

    private List<String> raised() {
        return book.claimUnrouted().stream()
                .map(RaisedAlert::alert)
                .map(alert -> alert.type().key() + " " + alert.level().key() + " " + alert.title())
                .toList();
    }

    @Test
    void aChangedThresholdAppliesAtTheNextReading() {
        reading = () -> stack(80, "running");
        monitor.poll();
        thresholds = new Thresholds(75, 90, 36);
        monitor.poll();
        assertEquals(List.of("disk warn The disk is 80 % full"), raised());
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
        assertEquals(List.of("disk warn The disk is 90 % full"), raised());

        // A number moving inside the same verdict is no news.
        reading = () -> stack(91, "running");
        monitor.poll();
        assertEquals(List.of(), raised());

        reading = () -> stack(10, "running");
        monitor.poll();
        assertEquals(List.of("disk ok All clear: disk"), raised());
        assertEquals(Alert.Level.OK, monitor.snapshot().level());
    }

    @Test
    void severalOfOneTypeAreOneAlertNamingThemAll() {
        monitor.poll();
        reading = () -> new StackReading(
                List.of(
                        new StackReading.Service("smp", "exited", null, false, false),
                        new StackReading.Service("proxy", "exited", null, false, false)),
                null,
                stack(10, "running").archives(),
                null);
        monitor.poll();
        final List<RaisedAlert> alerts = book.claimUnrouted();
        assertEquals(1, alerts.size());
        assertEquals("smp, proxy", alerts.getFirst().alert().subject());
        assertEquals("smp is not running and 1 more", alerts.getFirst().alert().title());
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
        final long failed = finished(UpdateStatus.FAILED);
        finished(UpdateStatus.DONE);

        monitor.runs();
        monitor.runs();

        final List<RaisedAlert> alerts = book.claimUnrouted();
        assertEquals(1, alerts.size());
        final Alert alert = alerts.getFirst().alert();
        assertEquals(AlertType.RUN, alert.type());
        assertEquals("The update run failed", alert.title());
        assertEquals("/operations/updates/" + failed, alert.path());
        assertNotNull(alerts.getFirst().raised());
    }

    private long finished(final UpdateStatus status) {
        final long id = runs.submit(UpdateKind.UPDATE, Actor.STEWARD, null).id();
        // steward-agent's half, as the owner it logs in as.
        final UpdateDirectory agent = UpdateDirectory.using(owner);
        assertEquals(id, agent.claimNext().orElseThrow().id());
        assertEquals(status, agent.finish(id, status, "{}").orElseThrow().status());
        return id;
    }
}
