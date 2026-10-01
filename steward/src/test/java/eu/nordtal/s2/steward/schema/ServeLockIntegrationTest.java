package eu.nordtal.s2.steward.schema;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.TestDatabase;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Exactly one {@code serve} runs against one database.
 *
 * A second would settle the first one's in-flight rows as orphans and throw its report away.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServeLockIntegrationTest {

    /** Short enough that the refusal is watched rather than waited out. */
    private static final Duration IMPATIENT = Duration.ofMillis(200);

    private static DataSource dataSource;

    @BeforeAll
    static void startPostgres() {
        final DataSource source = TestDatabase.fresh().dataSource();
        dataSource = source;
    }

    @Test
    void aSecondServeIsRefusedWhileTheFirstOneHoldsTheLock() throws SQLException {
        try (ServeLock first = ServeLock.acquire(dataSource, IMPATIENT, Waiting.on(Clock.systemUTC()))
                .orElseThrow()) {
            final Optional<ServeLock> second = ServeLock.acquire(dataSource, IMPATIENT, Waiting.on(Clock.systemUTC()));
            assertFalse(
                    second.isPresent(),
                    "a second serve loop took the lock. Both would then settle each other's"
                            + " in-flight requests as failures, and settleOrphans' whole"
                            + " justification stops being true.");
        }
    }

    @Test
    void theLockIsFreeAgainOnceTheFirstOneLetsGoARedeployHasToHandOver() throws SQLException {
        final ServeLock first = ServeLock.acquire(dataSource, IMPATIENT, Waiting.on(Clock.systemUTC()))
                .orElseThrow();
        first.close();

        final Optional<ServeLock> next = ServeLock.acquire(dataSource, IMPATIENT, Waiting.on(Clock.systemUTC()));
        assertTrue(next.isPresent(), "the replacement container could not take the freed lock");
        next.get().close();
    }

    @Test
    void itWaitsForAPredecessorRatherThanFailingTheInstantOneIsStillShuttingDown() throws Exception {
        final ServeLock leaving = ServeLock.acquire(dataSource, IMPATIENT, Waiting.on(Clock.systemUTC()))
                .orElseThrow();
        // The redeploy case: the replacement starts while the old container is still shutting down gracefully.
        final Thread shutdown = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            leaving.close();
        });
        shutdown.start();

        final Optional<ServeLock> replacement =
                ServeLock.acquire(dataSource, Duration.ofSeconds(10), Waiting.on(Clock.systemUTC()));
        shutdown.join();
        assertTrue(replacement.isPresent(), "the replacement gave up while its predecessor was still letting go");
        replacement.get().close();
    }

    @Test
    void theServeLockAndTheRunLockAreDifferentLocksSoServeCanStillBootstrap() throws SQLException {
        // serve holds this lock for its whole life, then bootstraps under RunLock; one key would self-deadlock.
        try (ServeLock serving = ServeLock.acquire(dataSource, IMPATIENT, Waiting.on(Clock.systemUTC()))
                        .orElseThrow();
                RunLock installing = RunLock.tryAcquire(dataSource).orElseThrow()) {
            assertTrue(serving != null && installing != null, "both held at once");
        }
    }
}
