package eu.nordtal.s2.proxy.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.velocitypowered.api.event.player.KickedFromServerEvent.DisconnectPlayer;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.limbo.WaitReason;
import eu.nordtal.s2.proxy.MutableClock;
import eu.nordtal.s2.proxy.pack.LimboHold;
import java.time.Instant;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/**
 * A backend that immediately kicks again must not bounce the player between {@code limbo} and itself.
 *
 * Composes {@link BackendKick#decide}, {@link BackendHealth} and {@link LimboHold#reason}, with nothing mocked.
 */
class BackendKickLoopTest {

    private static final String SMP = "smp";

    @Test
    void withoutTheBreakerASecondKickWouldFollowImmediately() {
        // Without BackendHealth, a crash-looping container still counts as available.
        assertEquals(
                BackendKick.Decision.TO_LIMBO,
                BackendKick.decide(DisconnectPlayer.create(Component.text("kicked")), null));

        final boolean registeredButNotHealthAware = true;
        assertEquals(
                Optional.empty(),
                LimboHold.reason(true, SeasonPhase.SMP, false, false, registeredButNotHealthAware, false, false),
                "with no health signal, the waiting room sees nothing standing between the player "
                        + "and the backend that just kicked them - which is the bounce");

        // And the second kick follows exactly like the first: the player bounces.
        assertEquals(
                BackendKick.Decision.TO_LIMBO,
                BackendKick.decide(DisconnectPlayer.create(Component.text("kicked again")), null));
    }

    @Test
    void withTheBreakerThePlayerWaitsOnceAndIsFetchedOnceSmpIsHealthy() {
        final MutableClock clock = new MutableClock(Instant.EPOCH);
        final BackendHealth health = new BackendHealth(clock);

        // Kick #1: no reason given, so BackendKick redirects to limbo and suspends 'smp'.
        assertEquals(
                BackendKick.Decision.TO_LIMBO,
                BackendKick.decide(DisconnectPlayer.create(Component.text("kicked")), null));
        health.suspend(SMP);

        // 'smp' is still registered, but the breaker holds the player with the same BACKEND title.
        assertEquals(
                Optional.of(WaitReason.BACKEND),
                LimboHold.reason(true, SeasonPhase.SMP, false, false, !health.isSuspended(SMP), false, false),
                "the player stays in the waiting room instead of bouncing straight back into 'smp'");

        // Time passes, but not yet the whole retry window: still held.
        clock.advance(BackendHealth.RETRY.minusSeconds(1));
        assertEquals(
                Optional.of(WaitReason.BACKEND),
                LimboHold.reason(true, SeasonPhase.SMP, false, false, !health.isSuspended(SMP), false, false));

        // The retry window passes; the next sweep may try again.
        clock.advance(java.time.Duration.ofSeconds(1));
        assertEquals(
                Optional.empty(),
                LimboHold.reason(true, SeasonPhase.SMP, false, false, !health.isSuspended(SMP), false, false),
                "once the retry window has passed the player may be released - this is the actual "
                        + "health check: a real connection attempt");

        // PlayerRouter clears the breaker when the connection succeeds.
        health.clear(SMP);
        assertEquals(
                Optional.empty(),
                LimboHold.reason(true, SeasonPhase.SMP, false, false, !health.isSuspended(SMP), false, false));
    }
}
