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
 * A value that goes out of date on a timer and is made new beside the reader, never by them.
 *
 * The mistake this replaces: The drift comparison was cached for a minute behind a plain "if it is older than the
 * TTL, read it again" - which quietly makes whoever asks first after the minute is up pay for a registry call over
 * the internet, with the rest of them queued behind the {@code synchronized}. Measured on the dev host:
 * {@code GET /api/services} took 11.5 s on that one request and 1.95 s on every other. steward-ui allows ten
 * seconds, so one request in sixty timed out and its log said steward-worker could not be reached - about a
 * container that was healthy, listening, and answering. Once a minute, for hours, until somebody read the log and
 * went looking at a network that was fine.
 *
 * The cache age was not the problem and is unchanged. Who pays for the refresh was.
 *
 * What it promises
 *
 * - The first read blocks, because there is nothing else to hand over - and it is the only one that ever does.
 *
 * - A stale value is handed over immediately, with one refresh started behind it. Ten readers arriving together
 * start one refresh between them, not ten.
 *
 * - A refresh that throws keeps the value that is there and lets the next reader try again. A registry that is down
 * is a drift column that stops being updated, not one that disappears - and {@link #refreshedAt()} is what makes
 * that visible rather than silent.
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

    /** The value as it stands - which is the newest one that finished, not the newest one asked for. */
    synchronized T get() {
        if (value == null) {
            // Nothing to serve; this one read is on the caller, and it is the only one that is.
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
                // The process is going away; the value here is still the right answer for requests still in the air.
                refreshing = false;
            }
        }
        return Objects.requireNonNull(value, "set together with refreshedAt");
    }

    /** When the value being handed out was read. The API sends it on, so a stale answer says so. */
    synchronized Instant refreshedAt() {
        return Objects.requireNonNull(refreshedAt, "read only after the first get()");
    }

    /**
     * Throws the cached value away, so the next {@link #get()} reads for real and blocks doing it.
     *
     * Why this drops the value instead of only aging it: Setting {@code refreshedAt} into the past would hand the
     * caller the old answer and refresh behind their back - which is right for a TTL expiring on its own and wrong
     * for somebody who just pressed a button labelled "read it again". They pressed it because the answer on the
     * page is the one they do not believe; giving it back to them, instantly, is the one response that cannot be
     * told apart from the button doing nothing.
     *
     * The cost is honest and belongs to the caller: the next read takes as long as asking GitHub, Modrinth and the
     * Fill API takes. Nothing else is blocked meanwhile - this lock is this cache's own.
     */
    synchronized void invalidate() {
        value = null;
    }

    private void refresh() {
        T fresh = null;
        try {
            fresh = read.get();
        } catch (RuntimeException failed) {
            // Warned rather than swallowed: the answer shown is the one from before, aged on the page beside it.
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
