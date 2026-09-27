package eu.nordtal.s2.steward.worker.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A value that goes out of date on a timer and is refreshed beside the reader, never by them.
 *
 * Only the first read blocks; a stale value is handed over at once and a failed refresh keeps it.
 */
final class Refreshed<T> {

    private static final Logger log = LoggerFactory.getLogger(Refreshed.class);

    private final Supplier<T> read;
    private final Duration ttl;
    private final Executor background;
    private final Supplier<Instant> clock;

    private @Nullable T value;
    private @Nullable Instant refreshedAt;
    private boolean refreshing;

    Refreshed(final Supplier<T> read, final Duration ttl, final Executor background, final Supplier<Instant> clock) {
        this.read = read;
        this.ttl = ttl;
        this.background = background;
        this.clock = clock;
    }

    /** The value as it stands: the newest one that finished, not the newest one asked for. */
    synchronized T get() {
        if (value == null) {
            // Nothing to serve, so this one read is on the caller.
            value = read.get();
            refreshedAt = clock.get();
            return value;
        }
        final Instant readAt = Objects.requireNonNull(refreshedAt, "set together with value");
        if (!refreshing && readAt.plus(ttl).isBefore(clock.get())) {
            refreshing = true;
            try {
                background.execute(this::refresh);
            } catch (RejectedExecutionException shuttingDown) {
                // The process is going away; the value is still right for requests in flight.
                refreshing = false;
            }
        }
        return Objects.requireNonNull(value, "set together with refreshedAt");
    }

    /** When the value being handed out was read, so a stale answer says so. */
    synchronized Instant refreshedAt() {
        return Objects.requireNonNull(refreshedAt, "read only after the first get()");
    }

    /**
     * Throws the cached value away, so the next {@link #get()} reads for real and blocks doing it.
     *
     * Dropped rather than aged, since the caller pressed a button because they doubt the current answer.
     */
    synchronized void invalidate() {
        value = null;
    }

    private void refresh() {
        T fresh = null;
        try {
            fresh = read.get();
        } catch (RuntimeException failed) {
            // Warned rather than swallowed: the page shows the old answer with its age.
            log.warn(
                    "could not refresh a cached answer - the one from {} stays on the page: {}",
                    refreshedAt(),
                    failed.toString());
        }
        synchronized (this) {
            if (fresh != null) {
                value = fresh;
                refreshedAt = clock.get();
            }
            refreshing = false;
        }
    }
}
