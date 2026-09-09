package eu.nordtal.s2.networkcontrol.pack;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.limbo.WaitReason;

import java.util.Objects;
import java.util.Optional;

/**
 * Whether a player in the waiting room may leave it yet, and if not, what they are waiting for.
 *
 * <p>No Velocity type is involved, which is what makes this the only part of the pack station
 * assertable without a running proxy; {@link PackStation} is everything else.</p>
 *
 * <p>The order of the questions is the design: the pack first, because it is the only one the
 * player can influence and the only one happening on their own machine right now; then maintenance,
 * a decision somebody took that only ends when an admin switches the phase; then the backend, which
 * resolves itself the moment the server comes up.</p>
 *
 * <p>Whether {@code limbo} has reported the player <em>ready</em> is deliberately not an input. It
 * is a fourth release condition handled by the caller, because it is the one that must never
 * produce a title - re-sending one between a finished download and {@code READY} would make the
 * waiting room flicker as it is about to disappear.</p>
 */
public final class LimboHold {

    private LimboHold() {
    }

    /**
     * @param packSettled           whether the pack is applied, or there is none to apply because
     *                              {@code pack.yml#enabled} is off - one input on purpose
     * @param phase                 the phase the network is in, from {@code PhaseWatch}
     * @param admin                 whether the player carries {@code discord_user.admin}; maintenance
     *                              does not hold the person carrying it out
     * @param destinationAvailable  whether the backend that phase points at is registered on this
     *                              proxy. A server that is registered but <em>down</em> is
     *                              indistinguishable from a healthy one here and shows up as a
     *                              failed connection after the release instead
     * @return what the waiting room should say, or empty when nothing is left to wait for and the
     *         player may be connected onward
     */
    public static Optional<WaitReason> reason(final boolean packSettled, final SeasonPhase phase,
                                              final boolean admin, final boolean destinationAvailable) {
        Objects.requireNonNull(phase, "phase");

        if (!packSettled) {
            return Optional.of(WaitReason.PACK);
        }
        if (phase == SeasonPhase.MAINTENANCE && !admin) {
            return Optional.of(WaitReason.MAINTENANCE);
        }
        if (!destinationAvailable) {
            return Optional.of(WaitReason.BACKEND);
        }
        return Optional.empty();
    }
}
