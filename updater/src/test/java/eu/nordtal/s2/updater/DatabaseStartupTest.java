package eu.nordtal.s2.updater;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.updater.config.DatabaseSpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

/**
 * That "PostgreSQL is not up yet" reads as a sentence rather than as a stack trace.
 *
 * <h2>Why it happens at all, and why that is fine</h2>
 * The updater deliberately has no {@code depends_on} for the database - {@code compose.yml} says
 * why - so on a first deployment it starts while PostgreSQL is still initialising, fails to
 * connect, and is restarted by its policy a few seconds later. The arrangement is correct. What was
 * wrong was the presentation: an uncaught {@code HikariPool$PoolInitializationException} with its
 * full trace, on the first screen of the first deployment, from the one container everything else
 * in the stack is waiting for. The module builds a careful named message for a config it refuses
 * and had none at all for this.
 */
class DatabaseStartupTest {

    @Test
    @DisplayName("a database that is not there yet returns null instead of throwing")
    void anUnreachableDatabaseIsNotAnUncaughtException() {
        // Port 1 on loopback: nothing listens, and the refusal is immediate rather than a timeout.
        final DatabaseSpec unreachable = new DatabaseSpec() {
            @Override
            public String jdbcUrl() {
                return "jdbc:postgresql://127.0.0.1:1/nordtal";
            }

            @Override
            public int queryTimeoutSeconds() {
                return 1;
            }
        };

        final Database opened = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> UpdaterMain.openDatabase(unreachable),
                "opening an unreachable database should fail fast, not hang the bootstrap");

        assertNull(opened,
                "openDatabase must answer null so the caller can exit with a sentence. Letting the"
                        + " HikariPool exception out is what put a stack trace on the first screen"
                        + " of the first deployment.");
    }
}
