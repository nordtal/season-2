package eu.nordtal.season.settings;

import eu.nordtal.season.common.time.Backoff;
import eu.nordtal.season.common.time.Waiting;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/** Opens a process's database pool, waiting for PostgreSQL to come up rather than exiting while it is not there. */
public final class DatabaseWaiting {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DatabaseWaiting.class);

    /** How long {@link #openDatabase(DatabaseSpec, String, Waiting)} asks before it calls the database absent. */
    public static final Duration DATABASE_WAIT = Duration.ofMinutes(3);

    /** The pause between two attempts. */
    public static final Duration DATABASE_RETRY = Duration.ofSeconds(2);

    private DatabaseWaiting() {}

    /**
     * Opens the pool, waiting for the database to appear; {@code null} means stop.
     *
     * Other services wait on this one being healthy, and compose abandons {@code up} if it exits, so it retries.
     */
    public static @Nullable Database openDatabase(final DatabaseSpec config, final String name, final Waiting waiting) {
        return openDatabase(config, name, DATABASE_WAIT, DATABASE_RETRY, waiting);
    }

    /** The same, with the windows as arguments so a test need not wait minutes. */
    public static @Nullable Database openDatabase(
            final DatabaseSpec config,
            final String name,
            final Duration wait,
            final Duration between,
            final Waiting waiting) {
        final AtomicReference<@Nullable RuntimeException> last = new AtomicReference<>();
        final Optional<Database> opened = waiting.until(
                () -> {
                    try {
                        return Optional.of(Database.open(config, name));
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
