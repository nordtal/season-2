package eu.nordtal.s2.steward.worker;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.steward.worker.config.DatabaseSpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

/**
 * That a database which is not up yet is waited for, and that giving up reads as a sentence.
 *
 * <h2>Why it happens at all, and what it costs</h2>
 * steward-worker deliberately has no {@code depends_on} for the database - {@code compose.yml} says
 * why - so on a first deployment it starts while PostgreSQL is still initialising and cannot
 * connect. Two things were wrong with the original answer to that, and only the first was fixed at
 * the time:
 *
 * <ul>
 *   <li>The presentation. An uncaught {@code HikariPool$PoolInitializationException} with its full
 *       trace, on the first screen of the first deployment, from the one container everything else
 *       in the stack is waiting for. The module builds a careful named message for a config it
 *       refuses and had none at all for this.</li>
 *   <li><b>The exit itself.</b> Every other service waits on this one through
 *       {@code depends_on: service_healthy}, and compose reads a container that exits during
 *       startup as a dependency that failed - not as one that is not ready. Measured on this host
 *       on 2026-09-14: PostgreSQL was about a second and a half short, the worker exited, the
 *       restart policy brought it back and it was healthy seconds later, and compose had already
 *       printed {@code dependency failed to start: container nordtal-s2-steward-worker-1 is
 *       unhealthy} and abandoned the deployment. Nothing else in the stack was ever created.</li>
 * </ul>
 *
 * <p>So the window below is the whole point: inside it, "not yet" is not an answer this process
 * gives anybody.</p>
 */
class DatabaseStartupTest {

    /**
     * Port 1 on loopback: nothing listens there, and the refusal is immediate rather than a
     * timeout - so what the test measures is the waiting this class does, not the network's.
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
    @DisplayName("a database that is not there is asked again, for the whole window")
    void anAbsentDatabaseIsWaitedFor() {
        final long before = System.nanoTime();
        final Database opened = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> StewardWorker.openDatabase(UNREACHABLE, Duration.ofSeconds(3),
                        Duration.ofMillis(200)),
                "waiting for the database must end at the window, not hang the bootstrap");
        final Duration waited = Duration.ofNanos(System.nanoTime() - before);

        assertNull(opened, "the window ran out, so this is a refusal");
        assertTrue(waited.compareTo(Duration.ofSeconds(3)) >= 0,
                "openDatabase came back after " + waited + ", which is less than the window it was"
                        + " given - so it gave up on the first refusal. That is the exit compose"
                        + " reads as a failed dependency, and it takes the whole first deployment"
                        + " down with it.");
    }

    @Test
    @DisplayName("a database that is still not there returns null instead of throwing")
    void anUnreachableDatabaseIsNotAnUncaughtException() {
        final Database opened = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> StewardWorker.openDatabase(UNREACHABLE, Duration.ZERO, Duration.ofMillis(200)),
                "opening an unreachable database should fail fast, not hang the bootstrap");

        assertNull(opened,
                "openDatabase must answer null so the caller can exit with a sentence. Letting the"
                        + " HikariPool exception out is what put a stack trace on the first screen"
                        + " of the first deployment.");
    }

    @Test
    @DisplayName("the window a deployment actually gets is minutes, not one attempt")
    void theWindowIsLongEnoughForPostgresToInitialise() {
        // Pinned because the default is the only one a deployment ever uses, and a plausible-looking
        // edit to it - seconds instead of minutes - would restore exactly the failure above without
        // either test noticing: both of those pass their own window in explicitly.
        assertTrue(StewardWorker.DATABASE_WAIT.compareTo(Duration.ofMinutes(1)) >= 0,
                "a first deployment initialises a PostgreSQL data directory before it listens;"
                        + " DATABASE_WAIT is " + StewardWorker.DATABASE_WAIT);
        assertTrue(StewardWorker.DATABASE_RETRY.compareTo(Duration.ofSeconds(10)) <= 0,
                "the pause between attempts is how late the worker notices a database that has"
                        + " arrived, and everything else in the stack is waiting behind it;"
                        + " DATABASE_RETRY is " + StewardWorker.DATABASE_RETRY);
    }
}
