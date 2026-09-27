package eu.nordtal.s2.proxy.pack;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.limbo.WaitReason;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether a player in the waiting room may leave it yet, and if not, what they are waiting for.
 *
 * The questions run from what the player can influence to what resolves itself; the standby never releases.
 */
public final class LimboHold {

    private LimboHold() {}

    /**
     * The first reason to keep waiting, or empty when the player may be connected onward.
     *
     * @param packSettled whether the pack is applied, or there is none because {@code pack.yml#enabled} is off
     * @param admin whether the player carries {@code discord_user.admin}; maintenance does not hold them
     * @param standby whether this is the standby proxy, which releases nobody, admins included
     * @param destinationAvailable whether that phase's backend is registered and not suspended
     * @param destinationUpdating whether an update run has that backend stopped
     * @param destinationHeld whether {@code service_hold} keeps that backend stopped
     */
    public static Optional<WaitReason> reason(
            final boolean packSettled,
            final SeasonPhase phase,
            final boolean admin,
            final boolean standby,
            final boolean destinationAvailable,
            final boolean destinationUpdating,
            final boolean destinationHeld) {
        Objects.requireNonNull(phase, "phase");

        if (!packSettled) {
            return Optional.of(WaitReason.PACK);
        }
        if (phase == SeasonPhase.MAINTENANCE && !admin) {
            return Optional.of(WaitReason.MAINTENANCE);
        }
        if (standby) {
            // UPDATE fits here too, and StandbyReturn keeps that promise without a seventh screen.
            return Optional.of(WaitReason.UPDATE);
        }
        if (destinationHeld) {
            // Above the update: a DOWN run ends but the hold it wrote does not, so the older truth wins.
            return Optional.of(WaitReason.HELD);
        }
        if (destinationUpdating) {
            // Not guarded by destinationAvailable: a stopped container stays registered for a moment.
            return Optional.of(WaitReason.UPDATE);
        }
        if (!destinationAvailable) {
            return Optional.of(WaitReason.BACKEND);
        }
        return Optional.empty();
    }
}
