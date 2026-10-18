package eu.nordtal.season.settings;

import eu.nordtal.season.common.time.Backoff;
import eu.nordtal.season.common.time.Waiting;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

public final class DatabaseWaiting {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DatabaseWaiting.class);

    public static final Duration DATABASE_WAIT = Duration.ofMinutes(3);

    public static final Duration DATABASE_RETRY = Duration.ofSeconds(2);

    private DatabaseWaiting() {}

    /**
     * Opens the pool and retries until the database answers or {@link #DATABASE_WAIT} has passed.
     *
     * Returns {@code null} if it gives up or the thread is interrupted.
     */
    public static @Nullable Database openDatabase(final DatabaseSpec config, final String name, final Waiting waiting) {
        return openDatabase(config, name, DATABASE_WAIT, DATABASE_RETRY, waiting);
    }

    public static @Nullable Database openDatabase(
            final DatabaseSpec config, final String name, final Duration queryBound, final Waiting waiting) {
        return openDatabase(config, name, queryBound, DATABASE_WAIT, DATABASE_RETRY, waiting);
    }

    public static @Nullable Database openDatabase(
            final DatabaseSpec config,
            final String name,
            final Duration wait,
            final Duration between,
            final Waiting waiting) {
        return openDatabase(config, name, Duration.ofSeconds(config.queryTimeoutSeconds()), wait, between, waiting);
    }

    private static @Nullable Database openDatabase(
            final DatabaseSpec config,
            final String name,
            final Duration queryBound,
            final Duration wait,
            final Duration between,
            final Waiting waiting) {
        final AtomicReference<@Nullable RuntimeException> last = new AtomicReference<>();
        final Optional<Database> opened = waiting.until(
                () -> {
                    try {
                        return Optional.of(Database.open(config, name, queryBound));
                    } catch (final RuntimeException unreachable) {
                        if (last.getAndSet(unreachable) == null) {
                            // Log once, so the retries do not bury the migration line.
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
