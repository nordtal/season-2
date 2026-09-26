package eu.nordtal.s2.proxy.gate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-server circuit breaker: one backend suspended, every other backend unaffected.
 *
 * Nobody's session is the thing being tracked.
 *
 * This proxy has no way to ask a backend "are you all right" that is more honest than trying to
 * connect a player to it - which is exactly what {@link eu.nordtal.s2.proxy.PlayerRouter} already
 * does on every release. So the breaker does not poll anything of its own: it goes open the
 * instant a backend is caught misbehaving, stays open for {@link #RETRY}, and then goes half-open -
 * {@link #isSuspended} answers {@code false} again for exactly the one question that follows, which
 * is what lets the ordinary release path in {@code PackStation} make the next real attempt. That
 * attempt is the health check: {@link #clear} on a connection that succeeds closes the breaker, and
 * whoever finds the backend still broken calls {@link #suspend} again, which reopens it for another
 * {@link #RETRY}.
 *
 * A Velocity connection attempt succeeding or failing is the same fact a player's own client would
 * see, obtained a moment earlier and without their having to press anything.
 *
 * What suspends a backend: {@link BackendKick}, when a player already on a backend is kicked with
 * no reason at all - see that class for why a missing reason is the signal for "this was not a
 * decision, the connection just died"; and {@code PackStation#releaseFailed}, when a release the
 * waiting room asked for could not even open a connection - the backend is registered but not
 * accepting anybody yet. Both are "this backend, not this player" facts, which is why this class is
 * keyed by server name and touched by neither {@code WaitingBook}'s per-session bookkeeping nor any
 * per-player identity.
 */
public final class BackendHealth {

    /**
     * How long a backend stays suspended before the next release attempt is allowed to test it again.
     *
     * Deliberately the same order of magnitude as {@code WaitingBook#RELEASE_RETRY} - both exist so a backend that just
     * failed is not immediately hammered again - but kept as its own constant rather than shared with it: one is a per-
     * session backoff and this is a per-server one, and nothing requires them to move together.
     */
    public static final Duration RETRY = Duration.ofSeconds(10);

    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> suspendedUntil = new ConcurrentHashMap<>();

    public BackendHealth(final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Opens the breaker for {@code server}: {@link #isSuspended} answers {@code true} until {@link #RETRY} passes.
     *
     * Calling this again before the window elapses restarts the window - a backend that fails twice in a row
     * does not get to average the two failures into a shorter one.
     *
     * @param server the backend name, or {@code null} to do nothing - callers pass through whatever
     *               {@code RegisteredServer#getServerInfo().getName()} returned them and none of
     *               them can prove it is non-null
     */
    public void suspend(final String server) {
        if (server != null) {
            suspendedUntil.put(server, clock.instant().plus(RETRY));
        }
    }

    /**
     * Closes the breaker for {@code server} immediately, before {@link #RETRY} would have.
     *
     * The caller has just watched a real connection to it succeed, which is a better answer than waiting out a timer
     * that was only ever a stand-in for one.
     *
     * @param server the backend name, or {@code null} to do nothing
     */
    public void clear(final String server) {
        if (server != null) {
            suspendedUntil.remove(server);
        }
    }

    /**
     * @param server the backend name, or {@code null}, which is never suspended
     * @return whether {@code server} is still inside its {@link #RETRY} window. {@code false} both
     *         for a backend nothing has ever suspended and for one whose window has passed - the
     *         caller cannot tell the two apart, and does not need to: either way the next attempt is
     *         allowed to go through
     */
    public boolean isSuspended(final String server) {
        if (server == null) {
            return false;
        }
        final Instant until = suspendedUntil.get(server);
        return until != null && clock.instant().isBefore(until);
    }
}
