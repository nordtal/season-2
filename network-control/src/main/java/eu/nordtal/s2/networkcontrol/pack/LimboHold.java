package eu.nordtal.s2.networkcontrol.pack;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.limbo.WaitReason;

import java.util.Objects;
import java.util.Optional;

/**
 * Whether a player in the waiting room may leave it yet, and if not, what they are waiting for.
 *
 * <p>Three facts decide it and none of them is a Velocity type, which is the point: this is the
 * rule docs/architecture.md#the-login-path-end-to-end draws as a sequence diagram, and it is the
 * only part of the pack station that can be asserted without a running proxy and a real client.
 * {@link PackStation} is everything else - the events, the connection, the offer - and is not
 * testable here at all.
 *
 * <h2>The order of the questions is the design</h2>
 * <ol>
 *   <li><b>The pack first.</b> It is the reason every login passes through here, it is the only
 *       one of the three the player can influence, and it is the only one that is happening
 *       <em>right now</em> on their own machine. Telling somebody the network is under maintenance
 *       while their client is mid-download describes the wrong half of their situation.</li>
 *   <li><b>Then maintenance</b>, because it is a decision somebody took rather than an accident,
 *       and because it is the one reason that does not end on its own - it ends when an admin
 *       switches the phase, and {@code PlayerRouter} moves everybody when that happens.</li>
 *   <li><b>Then the backend</b>, which is the accident: the phase's server is not registered on
 *       this proxy, or is registered and not answering. It resolves itself the moment the server
 *       comes up, which is why the proxy re-asks on a timer.</li>
 * </ol>
 *
 * <h2>What it deliberately does not take</h2>
 * Whether {@code limbo} has reported the player <em>ready</em>. That is a fourth condition on the
 * release, and it is handled by the caller rather than folded in here, because it is the one
 * condition that must never produce a title: in the moment between a finished download and
 * {@code limbo}'s {@code READY}, there is nothing true to tell the player that is not already on
 * their screen, and re-sending a title to say so would make the waiting room flicker at exactly
 * the moment it is about to disappear.
 */
public final class LimboHold {

    private LimboHold() {
    }

    /**
     * @param packSettled           whether the pack is applied - or whether there is no pack to
     *                              apply, because {@code pack.yml#enabled} is off. The two are one
     *                              input on purpose: a disabled pack is not a special case of the
     *                              waiting room, it is a waiting room with one fewer thing to wait
     *                              for
     * @param phase                 the phase the network is in, from {@code PhaseWatch}
     * @param destinationAvailable  whether the backend that phase points at is registered on this
     *                              proxy. A server that is registered but <em>down</em> is
     *                              indistinguishable from a healthy one here and shows up as a
     *                              failed connection after the release instead
     * @return what the waiting room should say, or empty when nothing is left to wait for and the
     *         player may be connected onward
     */
    public static Optional<WaitReason> reason(final boolean packSettled, final SeasonPhase phase,
                                              final boolean destinationAvailable) {
        Objects.requireNonNull(phase, "phase");

        if (!packSettled) {
            return Optional.of(WaitReason.PACK);
        }
        if (phase == SeasonPhase.MAINTENANCE) {
            return Optional.of(WaitReason.MAINTENANCE);
        }
        if (!destinationAvailable) {
            return Optional.of(WaitReason.BACKEND);
        }
        return Optional.empty();
    }
}
