package eu.nordtal.s2.settings;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.time.Waiting;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * A database that is not up yet is waited for, and giving up reads as a sentence rather than a stack trace.
 *
 * Exiting during startup would make compose abandon the whole deployment as a failed dependency.
 */
class DatabaseStartupTest {

    /**
     * Port 1 on loopback: nothing listens there, so the refusal is immediate and only this class's waiting is measured.
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
                () -> DatabaseWaiting.openDatabase(
                        UNREACHABLE,
                        "test",
                        Duration.ofSeconds(3),
                        Duration.ofMillis(200),
                        Waiting.on(Clock.systemUTC())),
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
                () -> DatabaseWaiting.openDatabase(
                        UNREACHABLE, "test", Duration.ZERO, Duration.ofMillis(200), Waiting.on(Clock.systemUTC())),
                "opening an unreachable database should fail fast, not hang the bootstrap");

        assertNull(
                opened,
                "openDatabase must answer null so the caller can exit with a sentence. Letting the"
                        + " HikariPool exception out is what put a stack trace on the first screen"
                        + " of the first deployment.");
    }

    @Test
    void theWindowADeploymentActuallyGetsIsMinutesNotOneAttempt() {
        // Seconds instead of minutes would bring the exit back; the other two tests pass their window explicitly.
        assertTrue(
                DatabaseWaiting.DATABASE_WAIT.compareTo(Duration.ofMinutes(1)) >= 0,
                "a first deployment initialises a PostgreSQL data directory before it listens;" + " DATABASE_WAIT is "
                        + DatabaseWaiting.DATABASE_WAIT);
        assertTrue(
                DatabaseWaiting.DATABASE_RETRY.compareTo(Duration.ofSeconds(10)) <= 0,
                "the pause between attempts is how late steward notices a database that has"
                        + " arrived, and everything else in the stack is waiting behind it;"
                        + " DATABASE_RETRY is " + DatabaseWaiting.DATABASE_RETRY);
    }
}
