package eu.nordtal.s2.steward.worker;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.steward.worker.config.DatabaseSpec;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * That a database which is not up yet is waited for, and that giving up reads as a sentence.
 *
 * Why it happens at all, and what it costs: steward-worker deliberately has no {@code depends_on} for the database -
 * {@code compose.yml} says why - so on a first deployment it starts while PostgreSQL is still initialising and
 * cannot connect. Two things were wrong with the original answer to that, and only the first was fixed at the time:
 *
 * - The presentation. An uncaught {@code HikariPool$PoolInitializationException} with its full trace, on the first
 * screen of the first deployment, from the one container everything else in the stack is waiting for. The module
 * builds a careful named message for a config it refuses and had none at all for this.
 *
 * - The exit itself. Every other service waits on this one through {@code depends_on: service_healthy}, and compose
 * reads a container that exits during startup as a dependency that failed - not as one that is not ready. A
 * PostgreSQL that is merely seconds from ready can still make the worker exit, and compose then prints
 * {@code dependency failed to start: container nordtal-s2-steward-worker-1 is unhealthy} and abandons the
 * deployment before anything else in the stack is created.
 *
 * So the window below is the whole point: inside it, "not yet" is not an answer this process gives anybody.
 */
class DatabaseStartupTest {

    /**
     * Port 1 on loopback: nothing listens there, and the refusal is immediate rather than a timeout.
     *
     * So what the test measures is the waiting this class does, not the network's.
     */
    private static final DatabaseSpec UNREACHABLE = new DatabaseSpec() {
        @Override
        public String jdbcUrl() {
            return "jdbc:postgresql://127.0.0.1:1/nordtal";
        }

        @Override
        public int queryTimeoutSeconds() {
            return 1;
        }
    };

    @Test
    void aDatabaseThatIsNotThereIsAskedAgainForTheWholeWindow() {
        final long before = System.nanoTime();
        final Database opened = assertTimeoutPreemptively(
                Duration.ofSeconds(30),
                () -> DatabaseWaiting.openDatabase(UNREACHABLE, Duration.ofSeconds(3), Duration.ofMillis(200)),
                "waiting for the database must end at the window, not hang the bootstrap");
        final Duration waited = Duration.ofNanos(System.nanoTime() - before);

        assertNull(opened, "the window ran out, so this is a refusal");
        assertTrue(
                waited.compareTo(Duration.ofSeconds(3)) >= 0,
                "openDatabase came back after " + waited + ", which is less than the window it was"
                        + " given - so it gave up on the first refusal. That is the exit compose"
                        + " reads as a failed dependency, and it takes the whole first deployment"
                        + " down with it.");
    }

    @Test
    void aDatabaseThatIsStillNotThereReturnsNullInsteadOfThrowing() {
        final Database opened = assertTimeoutPreemptively(
                Duration.ofSeconds(30),
                () -> DatabaseWaiting.openDatabase(UNREACHABLE, Duration.ZERO, Duration.ofMillis(200)),
                "opening an unreachable database should fail fast, not hang the bootstrap");

        assertNull(
                opened,
                "openDatabase must answer null so the caller can exit with a sentence. Letting the"
                        + " HikariPool exception out is what put a stack trace on the first screen"
                        + " of the first deployment.");
    }

    @Test
    void theWindowADeploymentActuallyGetsIsMinutesNotOneAttempt() {
        // Seconds instead of minutes would restore the failure above; the other two tests pass their window explicitly.
        assertTrue(
                DatabaseWaiting.DATABASE_WAIT.compareTo(Duration.ofMinutes(1)) >= 0,
                "a first deployment initialises a PostgreSQL data directory before it listens;" + " DATABASE_WAIT is "
                        + DatabaseWaiting.DATABASE_WAIT);
        assertTrue(
                DatabaseWaiting.DATABASE_RETRY.compareTo(Duration.ofSeconds(10)) <= 0,
                "the pause between attempts is how late the worker notices a database that has"
                        + " arrived, and everything else in the stack is waiting behind it;"
                        + " DATABASE_RETRY is " + DatabaseWaiting.DATABASE_RETRY);
    }
}
