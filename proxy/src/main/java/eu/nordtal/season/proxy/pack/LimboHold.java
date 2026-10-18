package eu.nordtal.season.proxy.pack;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.limboprotocol.WaitReason;
import java.util.Objects;
import java.util.Optional;

/** Decides whether a player may leave the waiting room. The standby proxy never releases a player. */
public final class LimboHold {

    private LimboHold() {}

    /**
     * Returns the first reason to keep the player waiting, or empty when the player may leave.
     *
     * @param packSettled whether the pack is applied or {@code pack#enabled} is off
     * @param admin whether the player is an admin, which {@code MAINTENANCE} does not hold
     * @param standby whether this proxy is the standby, which holds every player
     * @param destinationAvailable whether the backend of the phase is registered and not suspended
     * @param destinationUpdating whether an update run has stopped the backend
     * @param destinationHeld whether {@code service_hold} keeps the backend stopped
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
            return Optional.of(WaitReason.UPDATE);
        }
        if (destinationHeld) {
            return Optional.of(WaitReason.HELD);
        }
        if (destinationUpdating) {
            return Optional.of(WaitReason.UPDATE);
        }
        if (!destinationAvailable) {
            return Optional.of(WaitReason.BACKEND);
        }
        return Optional.empty();
    }
}
