package eu.nordtal.season.settings;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.TestDatabase;
import java.time.Clock;
import java.time.Duration;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.junit.jupiter.api.Test;

/** Checks that {@code query-timeout-seconds} bounds a running query in a pool however the process opened it. */
class DatabaseQueryBoundIntegrationTest {

    private static final String SLOW = "select pg_sleep(3)";

    private static DatabaseSpec spec(final TestDatabase database) {
        return new DatabaseSpec() {
            @Override
            public String jdbcUrl() {
                // The test URL sets socketTimeout=0, which would override the pool's setting.
                return database.jdbcUrl().replaceFirst("\\?.*", "");
            }

            @Override
            public String username() {
                return database.username();
            }

            @Override
            public String password() {
                return database.password();
            }

            @Override
            public int queryTimeoutSeconds() {
                return 1;
            }
        };
    }

    @Test
    void aPoolOpenedAsADatabaseCutsAQueryThatRunsPastTheTimeout() {
        final TestDatabase postgres = TestDatabase.fresh();
        try (Database database = Database.open(spec(postgres), "bounded")) {
            assertThrows(
                    UnableToExecuteStatementException.class,
                    () -> database.jdbi().withHandle(handle -> handle.execute(SLOW)));
        }
    }

    @Test
    void aPoolOpenedWhileWaitingForTheDatabaseCutsItToo() {
        final TestDatabase postgres = TestDatabase.fresh();
        try (Database database =
                requireNonNull(DatabaseWaiting.openDatabase(spec(postgres), "waited", Waiting.on(Clock.systemUTC())))) {
            assertThrows(
                    UnableToExecuteStatementException.class,
                    () -> database.jdbi().withHandle(handle -> handle.execute(SLOW)));
        }
    }

    @Test
    void anExplicitBoundLetsALongerQueryRunAndStillEndsOne() {
        final TestDatabase postgres = TestDatabase.fresh();
        try (Database database = requireNonNull(DatabaseWaiting.openDatabase(
                spec(postgres), "migrating", Duration.ofSeconds(10), Waiting.on(Clock.systemUTC())))) {
            assertDoesNotThrow(() -> database.jdbi().withHandle(handle -> handle.execute(SLOW)));
        }
        try (Database database = Database.open(spec(postgres), "short", Duration.ofSeconds(2))) {
            assertThrows(
                    UnableToExecuteStatementException.class,
                    () -> database.jdbi().withHandle(handle -> handle.execute(SLOW)));
        }
    }
}
