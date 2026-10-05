package eu.nordtal.season.database.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.TestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** A real {@code pg_notify} wakes a {@link SignalHub} without a poll; a killed connection is replaced and re-read. */
class SignalHubIntegrationTest {

    private static final String APPLICATION = "signal-hub-test";

    private static TestDatabase database;

    @BeforeAll
    static void startDatabase() {
        database = TestDatabase.fresh();
    }

    @Test
    void aNotifyFromAnotherConnectionWakesTheHubLongBeforeItsReconciliation() throws Exception {
        final Semaphore refreshed = new Semaphore(0);
        try (SignalHub hub = hub(Duration.ofMinutes(10), refreshed)) {
            hub.start();
            assertTrue(refreshed.tryAcquire(30, TimeUnit.SECONDS), "the connect's own re-read never ran");

            execute("SELECT pg_notify('" + Channel.PHASE.sqlName() + "', '')");

            assertTrue(refreshed.tryAcquire(10, TimeUnit.SECONDS), "the notification never woke the hub");
        }
    }

    @Test
    void aConnectionTheServerKillsIsReplacedAndReReadsAgain() throws Exception {
        final Semaphore refreshed = new Semaphore(0);
        try (SignalHub hub = hub(Duration.ofMinutes(10), refreshed)) {
            hub.start();
            assertTrue(refreshed.tryAcquire(30, TimeUnit.SECONDS), "the connect's own re-read never ran");

            assertEquals(
                    1,
                    // Found by its statement: the fixture's URL carries an application name that overrides the hub's.
                    count("SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity"
                            + " WHERE datname = current_database() AND query = 'LISTEN "
                            + Channel.PHASE.sqlName() + "'"),
                    "no listening connection to kill");

            assertTrue(refreshed.tryAcquire(30, TimeUnit.SECONDS), "the reconnect did not re-read");
            execute("SELECT pg_notify('" + Channel.PHASE.sqlName() + "', '')");
            assertTrue(refreshed.tryAcquire(10, TimeUnit.SECONDS), "the new connection does not listen");
        }
    }

    private static SignalHub hub(final Duration reconciliation, final Semaphore refreshed) {
        final SignalHub hub = new SignalHub(
                PostgresNotifications.connector(
                        database.jdbcUrl(), database.username(), database.password(), 5, APPLICATION),
                APPLICATION,
                LoggerFactory.getLogger(SignalHubIntegrationTest.class),
                reconciliation,
                Duration.ofMillis(100));
        hub.on(Channel.PHASE, "the counter", refreshed::release);
        return hub;
    }

    private static int count(final String sql) throws SQLException {
        try (Connection connection = database.dataSource().getConnection();
                Statement statement = connection.createStatement();
                java.sql.ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static void execute(final String sql) throws SQLException {
        try (Connection connection = database.dataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
