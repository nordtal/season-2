package eu.nordtal.s2.steward.worker;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.steward.worker.config.DatabaseSpec;
import eu.nordtal.s2.steward.worker.schema.Schema;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/** Opens the database pool, waiting for PostgreSQL to come up rather than exiting the moment it is not there yet. */
@Slf4j
final class DatabaseWaiting {

    /** How long {@link #openDatabase(DatabaseSpec)} keeps asking before it calls the database absent. */
    static final Duration DATABASE_WAIT = Duration.ofMinutes(3);

    /** The pause between two attempts. Short enough that a database appearing is noticed at once. */
    static final Duration DATABASE_RETRY = Duration.ofSeconds(2);

    private DatabaseWaiting() {}

    /**
     * Opens the pool, waiting for the database to appear. {@code null} means stop.
     *
     * Why it waits rather than exiting at once: There is deliberately no {@code depends_on} on the database (see
     * {@code compose.yml}), so on a first deployment this container starts while PostgreSQL is still initialising and
     * the pool cannot connect. The original answer was to exit and let {@code restart: unless-stopped} try again a few
     * seconds later, which does work - the container really is healthy half a minute later.
     *
     * It is not enough, and a first deployment is exactly where it breaks. Every other service in the stack waits on
     * this one through {@code depends_on: service_healthy}, and compose does not treat an exit during startup as "not
     * ready yet" - it treats it as a dependency that failed, prints
     * {@code dependency failed to start: container nordtal-s2-steward-worker-1 is unhealthy} and abandons the whole
     * {@code up}. A worker that exits once because PostgreSQL is a second short, then comes back on its own and is
     * healthy, is too late: compose has already given up and taken nothing else with it. A process that
     * is going to be waited for cannot answer "come back later" by dying.
     *
     * So it asks again for {@link #DATABASE_WAIT}, and only the end of that window is a refusal. The refusal keeps what
     * it always had: a named sentence, no stack trace - what an operator saw before this was caught was a whole
     * {@code HikariPool$PoolInitializationException} on the very first screen of the very first deployment, which reads
     * as a broken deployment when it is a normal one - and a non-zero exit that the restart policy turns into another
     * try.
     */
    static @Nullable Database openDatabase(final DatabaseSpec config) {
        return openDatabase(config, DATABASE_WAIT, DATABASE_RETRY);
    }

    /** @see #openDatabase(DatabaseSpec) - the windows are arguments so a test need not wait minutes. */
    static @Nullable Database openDatabase(final DatabaseSpec config, final Duration wait, final Duration between) {
        final long deadline = System.nanoTime() + wait.toNanos();
        boolean announced = false;
        while (true) {
            try {
                return Schema.open(config);
            } catch (final RuntimeException unreachable) {
                if (System.nanoTime() >= deadline) {
                    log.error(
                            "The database at {} did not answer within {}: {}. Check"
                                    + " POSTGRES_PASSWORD and that the `db` profile is in"
                                    + " COMPOSE_PROFILES; the restart policy will try again.",
                            config.jdbcUrl(),
                            wait,
                            rootCauseOf(unreachable));
                    return null;
                }
                if (!announced) {
                    // Once, not once per attempt: ninety copies on a first deployment would bury the migration line.
                    log.info(
                            "The database at {} is not answering yet ({}). That is expected on a"
                                    + " first deployment - PostgreSQL is still initialising and this"
                                    + " container has no depends_on for it, deliberately. Asking"
                                    + " again every {} for up to {}.",
                            config.jdbcUrl(),
                            rootCauseOf(unreachable),
                            between,
                            wait);
                    announced = true;
                }
                try {
                    Thread.sleep(between);
                } catch (final InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    log.error("Interrupted while waiting for the database at {}.", config.jdbcUrl());
                    return null;
                }
            }
        }
    }

    /** The innermost message, which is the one that says what actually happened. */
    private static String rootCauseOf(final Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && !cause.getCause().equals(cause)) {
            cause = cause.getCause();
        }
        final String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }
}
