package eu.nordtal.s2.steward.worker.schema;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * That exactly one {@code serve} can run against one database.
 *
 * What breaks without it: {@code UpdateServer.settleOrphans()} closes every row left {@code RUNNING} because
 * "nothing is running those rows: the only process that claims one is an updater, and this one has just started".
 * With two serve loops that reasoning is simply false - the second one marks the first one's in-flight {@code APPLY}
 * as {@code FAILED}, the real one's {@code finish(...)} then matches no {@code RUNNING} row, and the report of the
 * run that was actually installing jars is thrown away and replaced by "steward-worker stopped while this request
 * was running".
 *
 * Two of them was not a hypothetical: {@code docker compose run} inherited the service's {@code command} and started
 * a second daemon every time somebody asked for the read-only report.
 *
 * A real PostgreSQL, because an advisory lock is a property of a database session and "a second connection is
 * refused" has no in-JVM stand-in. Skips itself when no Docker daemon is reachable - a green build on a machine
 * without Docker proves nothing here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServeLockIntegrationTest {

    /** Short enough that the refusal is watched rather than waited out. */
    private static final Duration IMPATIENT = Duration.ofMillis(200);

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startPostgres() {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "no Docker daemon - this test can say nothing without one");
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();

        final PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        dataSource = source;
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void aSecondServeIsRefusedWhileTheFirstOneHoldsTheLock() throws SQLException {
        try (ServeLock first = ServeLock.acquire(dataSource, IMPATIENT).orElseThrow()) {
            final Optional<ServeLock> second = ServeLock.acquire(dataSource, IMPATIENT);
            assertFalse(
                    second.isPresent(),
                    "a second serve loop took the lock. Both would then settle each other's"
                            + " in-flight requests as failures, and settleOrphans' whole"
                            + " justification stops being true.");
        }
    }

    @Test
    void theLockIsFreeAgainOnceTheFirstOneLetsGoARedeployHasToHandOver() throws SQLException {
        final ServeLock first = ServeLock.acquire(dataSource, IMPATIENT).orElseThrow();
        first.close();

        final Optional<ServeLock> next = ServeLock.acquire(dataSource, IMPATIENT);
        assertTrue(next.isPresent(), "the replacement container could not take the freed lock");
        next.get().close();
    }

    @Test
    void itWaitsForAPredecessorRatherThanFailingTheInstantOneIsStillShuttingDown() throws Exception {
        final ServeLock leaving = ServeLock.acquire(dataSource, IMPATIENT).orElseThrow();
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

        final Optional<ServeLock> replacement = ServeLock.acquire(dataSource, Duration.ofSeconds(10));
        shutdown.join();
        assertTrue(replacement.isPresent(), "the replacement gave up while its predecessor was still letting go");
        replacement.get().close();
    }

    @Test
    void theServeLockAndTheRunLockAreDifferentLocksSoServeCanStillBootstrap() throws SQLException {
        // serve holds this lock for its whole life, then bootstraps under RunLock; one key would self-deadlock.
        try (ServeLock serving = ServeLock.acquire(dataSource, IMPATIENT).orElseThrow();
                RunLock installing = RunLock.tryAcquire(dataSource).orElseThrow()) {
            assertTrue(serving != null && installing != null, "both held at once");
        }
    }
}
