package eu.nordtal.s2.proxy.gate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-server circuit breaker, opened by a failed connection and tested again by the next real release.
 *
 * {@link BackendKick} and {@code PackStation#releaseFailed} suspend; a successful connection {@link #clear}s.
 */
public final class BackendHealth {

    /** How long a backend stays suspended before the next release attempt may test it again. */
    public static final Duration RETRY = Duration.ofSeconds(10);

    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> suspendedUntil = new ConcurrentHashMap<>();

    public BackendHealth(final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Opens the breaker for {@code server} until {@link #RETRY} passes, restarting the window if it is open.
     *
     * @param server the backend name, or {@code null} to do nothing
     */
    public void suspend(final String server) {
        if (server != null) {
            suspendedUntil.put(server, clock.instant().plus(RETRY));
        }
    }

    /**
     * Closes the breaker for {@code server} after a real connection to it succeeded.
     *
     * @param server the backend name, or {@code null} to do nothing
     */
    public void clear(final String server) {
        if (server != null) {
            suspendedUntil.remove(server);
        }
    }

    /** Whether {@code server} is inside its {@link #RETRY} window; never for {@code null} or an unknown name. */
    public boolean isSuspended(final String server) {
        if (server == null) {
            return false;
        }
        final Instant until = suspendedUntil.get(server);
        return until != null && clock.instant().isBefore(until);
    }
}
