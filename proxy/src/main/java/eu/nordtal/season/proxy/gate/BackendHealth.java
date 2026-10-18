package eu.nordtal.season.proxy.gate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records which backends are suspended. {@link BackendKick} and {@code PackStation#releaseFailed} suspend a backend.
 */
public final class BackendHealth {

    /** The time that a backend stays suspended. */
    public static final Duration RETRY = Duration.ofSeconds(10);

    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> suspendedUntil = new ConcurrentHashMap<>();

    public BackendHealth(final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Suspends the server for {@link #RETRY}. A {@code null} server is ignored. */
    public void suspend(final String server) {
        if (server != null) {
            suspendedUntil.put(server, clock.instant().plus(RETRY));
        }
    }

    /** Ends the suspension of the server. A {@code null} server is ignored. */
    public void clear(final String server) {
        if (server != null) {
            suspendedUntil.remove(server);
        }
    }

    /** Returns whether the server is suspended. It is {@code false} for {@code null} and for an unknown server. */
    public boolean isSuspended(final String server) {
        if (server == null) {
            return false;
        }
        final Instant until = suspendedUntil.get(server);
        return until != null && clock.instant().isBefore(until);
    }
}
