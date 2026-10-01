package eu.nordtal.s2.steward;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.common.time.Backoff;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.settings.DatabaseSpec;
import eu.nordtal.s2.steward.schema.Schema;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/** Opens the database pool, waiting for PostgreSQL to come up rather than exiting the moment it is not there yet. */
@Slf4j
final class DatabaseWaiting {

    /** How long {@link #openDatabase(DatabaseSpec, Waiting)} keeps asking before it calls the database absent. */
    static final Duration DATABASE_WAIT = Duration.ofMinutes(3);

    /** The pause between two attempts. */
    static final Duration DATABASE_RETRY = Duration.ofSeconds(2);

    private DatabaseWaiting() {}

    /**
     * Opens the pool, waiting for the database to appear; {@code null} means stop.
     *
     * Other services wait on this one being healthy, and compose abandons {@code up} if it exits, so it retries.
     */
    static @Nullable Database openDatabase(final DatabaseSpec config, final Waiting waiting) {
        return openDatabase(config, DATABASE_WAIT, DATABASE_RETRY, waiting);
    }

    /** The same, with the windows as arguments so a test need not wait minutes. */
    static @Nullable Database openDatabase(
            final DatabaseSpec config, final Duration wait, final Duration between, final Waiting waiting) {
        final AtomicReference<@Nullable RuntimeException> last = new AtomicReference<>();
        final Optional<Database> opened = waiting.until(
                () -> {
                    try {
                        return Optional.of(Schema.open(config));
                    } catch (final RuntimeException unreachable) {
                        if (last.getAndSet(unreachable) == null) {
                            // Once, not per attempt, so the retries do not bury the migration line.
                            log.info(
                                    "The database at {} is not answering yet ({}). That is expected on a"
                                            + " first deployment - PostgreSQL is still initialising and this"
                                            + " container has no depends_on for it, deliberately. Asking"
                                            + " again every {} for up to {}.",
                                    config.jdbcUrl(),
                                    rootCauseOf(unreachable),
                                    between,
                                    wait);
                        }
                        return Optional.empty();
                    }
                },
                wait,
                Backoff.fixed(between));
        if (opened.isPresent()) {
            return opened.get();
        }
        if (Thread.currentThread().isInterrupted()) {
            log.error("Interrupted while waiting for the database at {}.", config.jdbcUrl());
            return null;
        }
        final RuntimeException failure = last.get();
        log.error(
                "The database at {} did not answer within {}: {}. Check"
                        + " POSTGRES_PASSWORD and that the `db` profile is in"
                        + " COMPOSE_PROFILES; the restart policy will try again.",
                config.jdbcUrl(),
                wait,
                failure == null ? "no answer" : rootCauseOf(failure));
        return null;
    }

    private static String rootCauseOf(final Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && !cause.getCause().equals(cause)) {
            cause = cause.getCause();
        }
        final String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }
}
