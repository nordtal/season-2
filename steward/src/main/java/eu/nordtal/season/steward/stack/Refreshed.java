package eu.nordtal.season.steward.stack;

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
 * A cached value that a background executor refreshes after its time to live.
 * Only the first read blocks. A stale read returns the old value, and a failed refresh keeps it.
 */
public final class Refreshed<T> {

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

    synchronized T get() {
        if (value == null) {
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
                refreshing = false;
            }
        }
        return Objects.requireNonNull(value, "set together with refreshedAt");
    }

    synchronized Instant refreshedAt() {
        return Objects.requireNonNull(refreshedAt, "read only after the first get()");
    }

    /** Drops the cached value, so the next {@link #get()} reads and blocks. */
    synchronized void invalidate() {
        value = null;
    }

    private void refresh() {
        T fresh = null;
        try {
            fresh = read.get();
        } catch (RuntimeException failed) {
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
