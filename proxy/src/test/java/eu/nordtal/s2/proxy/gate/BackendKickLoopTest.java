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
 * Nothing here can build a real {@code KickedFromServerEvent} or a real connection - see
 * {@link BackendKick} and {@code PackStation} for why the pieces that can be asserted are the pure
 * ones. What this composes instead is exactly the seam {@code PackStation#evaluate} walks in
 * production: {@link BackendKick#decide} says what a kick with no reason becomes,
 * {@link BackendHealth} is what that decision suspends, and {@link LimboHold#reason} is what a
 * suspended destination turns into on the waiting room's own screen. Together they are the whole
 * mechanism; nothing below is mocked.
 */
class BackendKickLoopTest {

    private static final String SMP = "smp";

    @Test
    void withoutTheBreakerASecondKickWouldFollowImmediately() {
        // Without BackendHealth, "available" only asks whether registered; a crash-looping container still passes.
        assertEquals(
                BackendKick.Decision.TO_LIMBO,
                BackendKick.decide(DisconnectPlayer.create(Component.text("kicked")), null));

        final boolean registeredButNotHealthAware = true;
        assertEquals(
                Optional.empty(),
                LimboHold.reason(true, SeasonPhase.SMP, false, false, registeredButNotHealthAware, false, false),
                "with no health signal, the waiting room sees nothing standing between the player "
                        + "and the backend that just kicked them - which is the bounce");

        // And the second kick follows, exactly like the first - this is the "pendelt" outcome.
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

        // 'smp' is still registered, but the breaker is open, so the player is held with the same BACKEND title.
        assertEquals(
                Optional.of(WaitReason.BACKEND),
                LimboHold.reason(true, SeasonPhase.SMP, false, false, !health.isSuspended(SMP), false, false),
                "the player stays in the waiting room instead of bouncing straight back into 'smp'");

        // Time passes, but not yet the whole retry window: still held.
        clock.advance(BackendHealth.RETRY.minusSeconds(1));
        assertEquals(
                Optional.of(WaitReason.BACKEND),
                LimboHold.reason(true, SeasonPhase.SMP, false, false, !health.isSuspended(SMP), false, false));

        // The retry window passes; the next sweep may try again - "regelmäßig prüfen", not a second kick.
        clock.advance(java.time.Duration.ofSeconds(1));
        assertEquals(
                Optional.empty(),
                LimboHold.reason(true, SeasonPhase.SMP, false, false, !health.isSuspended(SMP), false, false),
                "once the retry window has passed the player may be released - this is the actual "
                        + "health check: a real connection attempt");

        // PlayerRouter clears the breaker on the connection's own success - "wird verbunden, ohne etwas zu tun".
        health.clear(SMP);
        assertEquals(
                Optional.empty(),
                LimboHold.reason(true, SeasonPhase.SMP, false, false, !health.isSuspended(SMP), false, false));
    }
}
