package eu.nordtal.s2.proxy.gate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.proxy.MutableClock;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The per-server breaker on its own, with no {@link BackendKick} or Velocity connection anywhere
 * near it - see {@link BackendHealth} for why a timeout stands in for a real health check.
 */
class BackendHealthTest {

    @Test
    void aServerNeverSuspendedIsAvailable() {
        assertFalse(new BackendHealth(new MutableClock(Instant.EPOCH)).isSuspended("smp"));
    }

    @Test
    void suspendingOneServerLeavesEveryOtherOneUntouched() {
        final BackendHealth health = new BackendHealth(new MutableClock(Instant.EPOCH));
        health.suspend("smp");
        assertTrue(health.isSuspended("smp"));
        assertFalse(health.isSuspended("hunger-games"));
        assertFalse(health.isSuspended("limbo"));
    }

    @Test
    void theSuspensionExpiresOnItsOwnAfterRetry() {
        final MutableClock clock = new MutableClock(Instant.EPOCH);
        final BackendHealth health = new BackendHealth(clock);
        health.suspend("smp");

        clock.advance(BackendHealth.RETRY.minusSeconds(1));
        assertTrue(health.isSuspended("smp"), "still inside the window");

        clock.advance(java.time.Duration.ofSeconds(1));
        assertFalse(health.isSuspended("smp"), "the window has passed - the next attempt may go through");
    }

    @Test
    void aSecondFailureBeforeTheWindowEndsRestartsIt() {
        final MutableClock clock = new MutableClock(Instant.EPOCH);
        final BackendHealth health = new BackendHealth(clock);
        health.suspend("smp");

        clock.advance(BackendHealth.RETRY.minusSeconds(1));
        health.suspend("smp");

        // Had the second suspend() not restarted the window, one more second would have cleared it.
        clock.advance(java.time.Duration.ofSeconds(1));
        assertTrue(health.isSuspended("smp"), "a fresh failure resets the window rather than shortening it");
    }

    @Test
    void clearClosesTheBreakerBeforeTheWindowWouldHave() {
        final MutableClock clock = new MutableClock(Instant.EPOCH);
        final BackendHealth health = new BackendHealth(clock);
        health.suspend("smp");
        health.clear("smp");
        assertFalse(health.isSuspended("smp"));
    }

    @Test
    void nullIsNeverSuspendedAndNeverThrows() {
        final BackendHealth health = new BackendHealth(new MutableClock(Instant.EPOCH));
        health.suspend(null);
        health.clear(null);
        assertFalse(health.isSuspended(null));
    }
}
