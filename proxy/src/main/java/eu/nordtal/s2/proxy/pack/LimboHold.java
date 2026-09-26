package eu.nordtal.s2.proxy.pack;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.limbo.WaitReason;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether a player in the waiting room may leave it yet, and if not, what they are waiting for.
 *
 * No Velocity type is involved, which is what makes this the only part of the pack station
 * assertable without a running proxy; {@link PackStation} is everything else.
 *
 * The order of the questions is the design: the pack first, because it is the only one the
 * player can influence and the only one happening on their own machine right now; then maintenance,
 * a decision somebody took that only ends when an admin switches the phase; then the swap, which is
 * a fact about this whole process rather than about one destination; then the
 * hold, which is also a decision and also does not end on its own; then the
 * update, which somebody started deliberately and which is nearly over; then the backend, which
 * resolves itself the moment the server comes up.
 *
 * The standby answers this question once, for everybody, and the answer is always yes. A standby
 * proxy exists for the twenty seconds the live one takes to restart, and every player on it is there
 * because they were parked. There is nothing to release them to: the backend the phase names is up
 * and healthy and completely beside the point, because they are going home to the other proxy, not
 * onward from this one. Releasing them would connect them to a Paper server they are about to be
 * pulled off again - and for the ones parked out of {@code limbo}, it would rejoin them to the very
 * backend they left a second ago.
 *
 * It is asked above the hold and the update rather than below them because it is the broader
 * truth: those two are about the destination, and this is about the process asking. On the live
 * proxy it is false and this whole paragraph costs a branch.
 *
 * The update sits directly above the backend, and that placement is the whole of it. From
 * out here the two are indistinguishable - in both cases the destination is not taking connections -
 * so without this question a player moved aside for an update would read "Waiting for the server",
 * which is what the screen says when something has gone wrong and nobody knows for how long. It is
 * below maintenance because maintenance is the longer-lived truth: an update inside a
 * maintenance window ends and leaves the player exactly where the maintenance title already said
 * they would be.
 *
 * Whether {@code limbo} has reported the player ready is deliberately not an input. It
 * is a fourth release condition handled by the caller, because it is the one that must never
 * produce a title - re-sending one between a finished download and {@code READY} would make the
 * waiting room flicker as it is about to disappear.
 */
public final class LimboHold {

    private LimboHold() {}

    /**
     * @param packSettled           whether the pack is applied, or there is none to apply because
     *                              {@code pack.yml#enabled} is off - one input on purpose
     * @param phase                 the phase the network is in, from {@code PhaseWatch}
     * @param admin                 whether the player carries {@code discord_user.admin}; maintenance
     *                              does not hold the person carrying it out
     * @param standby               whether this process is the standby proxy, in which case nobody
     *                              is released at all - see the class comment. Admins included:
     *                              an admin parked here is going home like everybody else, and
     *                              releasing them onto a backend would strand them on the proxy
     *                              that is about to be stopped
     * @param destinationAvailable  whether the backend that phase points at is registered on this
     *                              proxy. A server that is registered but down is
     *                              indistinguishable from a healthy one here and shows up as a
     *                              failed connection after the release instead
     * @param destinationUpdating   whether an update run has that same backend stopped. Read from
     *                              the update row rather than from the connection, because nothing
     *                              about a socket that will not open says who closed it or why
     * @param destinationHeld       whether somebody stopped that backend on purpose and it stays
     *                              stopped until they start it again. Read from
     *                              {@code service_hold}, for the same reason: the socket cannot
     *                              say
     * @return what the waiting room should say, or empty when nothing is left to wait for and the
     *         player may be connected onward
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
            // UPDATE fits here too, and StandbyReturn keeps that promise without a seventh screen repeating it.
            return Optional.of(WaitReason.UPDATE);
        }
        if (destinationHeld) {
            // Above the update on purpose: a DOWN run ends but the hold it wrote does not, so the older truth wins.
            return Optional.of(WaitReason.HELD);
        }
        if (destinationUpdating) {
            // Not guarded by destinationAvailable: a stopped container stays registered, briefly reading available.
            return Optional.of(WaitReason.UPDATE);
        }
        if (!destinationAvailable) {
            return Optional.of(WaitReason.BACKEND);
        }
        return Optional.empty();
    }
}
