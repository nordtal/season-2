package eu.nordtal.s2.steward.worker.api;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * A value that goes out of date on a timer and is made new beside the reader, never by them.
 *
 * <h2>The mistake this replaces</h2>
 * The drift comparison was cached for a minute behind a plain "if it is older than the TTL, read it
 * again" - which quietly makes whoever asks first after the minute is up pay for a registry call
 * over the internet, with the rest of them queued behind the {@code synchronized}. Measured on the
 * dev host on 2026-09-14: {@code GET /api/services} took <b>11.5 s</b> on that one request and
 * <b>1.95 s</b> on every other. steward-ui allows ten seconds, so one request in sixty timed out
 * and its log said <em>steward-worker could not be reached</em> - about a container that was
 * healthy, listening, and answering. Once a minute, for hours, until somebody read the log and went
 * looking at a network that was fine.
 *
 * <p>The cache age was not the problem and is unchanged. Who pays for the refresh was.</p>
 *
 * <h2>What it promises</h2>
 * <ul>
 *   <li>The first read blocks, because there is nothing else to hand over - and it is the only one
 *       that ever does.</li>
 *   <li>A stale value is handed over <em>immediately</em>, with one refresh started behind it. Ten
 *       readers arriving together start one refresh between them, not ten.</li>
 *   <li>A refresh that throws keeps the value that is there and lets the next reader try again. A
 *       registry that is down is a drift column that stops being updated, not one that disappears -
 *       and {@link #refreshedAt()} is what makes that visible rather than silent.</li>
 * </ul>
 */
final class Refreshed<T> {

    private static final Logger log = LoggerFactory.getLogger(Refreshed.class);

    private final Supplier<T> read;
    private final Duration ttl;
    private final Executor background;
    private final Supplier<Instant> clock;

    private T value;
    private Instant refreshedAt;
    private boolean refreshing;

    Refreshed(final @NotNull Supplier<T> read, final @NotNull Duration ttl,
              final @NotNull Executor background, final @NotNull Supplier<Instant> clock) {
        this.read = read;
        this.ttl = ttl;
        this.background = background;
        this.clock = clock;
    }

    /** The value as it stands - which is the newest one that finished, not the newest one asked for. */
    synchronized @NotNull T get() {
        if (value == null) {
            // Nothing to serve. This one read is on the caller, and it is the only one that is.
            value = read.get();
            refreshedAt = clock.get();
            return value;
        }
        if (!refreshing && refreshedAt.plus(ttl).isBefore(clock.get())) {
            refreshing = true;
            try {
                background.execute(this::refresh);
            } catch (RejectedExecutionException shuttingDown) {
                // The process is going away. The value that is here is still the right answer for
                // whatever requests are still in the air.
                refreshing = false;
            }
        }
        return value;
    }

    /** When the value being handed out was read. The API sends it on, so a stale answer says so. */
    synchronized @NotNull Instant refreshedAt() {
        return refreshedAt;
    }

    private void refresh() {
        T fresh = null;
        try {
            fresh = read.get();
        } catch (RuntimeException failed) {
            // Warned rather than swallowed, and it is actionable: the answer on the page is the
            // one from before, and its age is on the page beside it.
            log.warn("could not refresh a cached answer - the one from {} stays on the page: {}",
                    refreshedAt(), failed.toString());
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
