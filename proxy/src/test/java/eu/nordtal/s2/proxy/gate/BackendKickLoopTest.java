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
 * season-2-ops/20's own red test: "ein Backend, das sofort wieder wirft, hielte den Spieler sonst
 * in einer Endlosschleife" - a backend that immediately kicks again must not bounce the player
 * between {@code limbo} and itself on every attempt.
 *
 * <p>Nothing here can build a real {@code KickedFromServerEvent} or a real connection - see
 * {@link BackendKick} and {@code PackStation} for why the pieces that can be asserted are the pure
 * ones. What this composes instead is exactly the seam {@code PackStation#evaluate} walks in
 * production: {@link BackendKick#decide} says what a kick with no reason becomes,
 * {@link BackendHealth} is what that decision suspends, and {@link LimboHold#reason} is what a
 * suspended destination turns into on the waiting room's own screen. Together they are the whole
 * mechanism; nothing below is mocked.</p>
 */
class BackendKickLoopTest {

    private static final String SMP = "smp";

    @Test
    void withoutTheBreakerASecondKickWouldFollowImmediately() {
        // The situation this class exists to fix: a backend that kicks with no reason twice in a
        // row. Without BackendHealth in the loop, "is smp available" only ever asks whether it is
        // *registered* on the proxy - which a crash-looping container still is - so the player is
        // released straight back into it and is kicked again on arrival.
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

        // The waiting room's own sweep re-asks immediately, as it does every
        // gate.yml#limbo-sweep-interval-seconds. 'smp' is still registered, but the breaker is open,
        // so the player is held with the same BACKEND title the room already uses for a destination
        // that is merely down - not released, and therefore not kicked a second time.
        assertEquals(
                Optional.of(WaitReason.BACKEND),
                LimboHold.reason(true, SeasonPhase.SMP, false, false, !health.isSuspended(SMP), false, false),
                "the player stays in the waiting room instead of bouncing straight back into 'smp'");

        // Time passes, but not yet the whole retry window: still held.
        clock.advance(BackendHealth.RETRY.minusSeconds(1));
        assertEquals(
                Optional.of(WaitReason.BACKEND),
                LimboHold.reason(true, SeasonPhase.SMP, false, false, !health.isSuspended(SMP), false, false));

        // The retry window passes. The next sweep is allowed to try again - this is
        // "regelmäßig prüfen", not a second kick.
        clock.advance(java.time.Duration.ofSeconds(1));
        assertEquals(
                Optional.empty(),
                LimboHold.reason(true, SeasonPhase.SMP, false, false, !health.isSuspended(SMP), false, false),
                "once the retry window has passed the player may be released - this is the actual "
                        + "health check: a real connection attempt");

        // Say that attempt succeeded: PlayerRouter would clear the breaker on the connection's own
        // success, and the player is on 'smp' having done nothing themselves - "wird verbunden, ohne
        // dass er etwas tun muss".
        health.clear(SMP);
        assertEquals(
                Optional.empty(),
                LimboHold.reason(true, SeasonPhase.SMP, false, false, !health.isSuspended(SMP), false, false));
    }
}
