package eu.nordtal.s2.networkcontrol.pack;

import eu.nordtal.s2.common.limbo.WaitReason;

import java.util.Objects;

/**
 * What to do with one player in the waiting room, right now. The output of {@link WaitingBook}, and
 * the same shape as {@code RouteDecision} for the same reason: the rule is worth asserting on its
 * own, and it cannot be if the only way to observe it is a Velocity connection.
 *
 * @param action what should happen
 * @param reason the title to show, non-{@code null} exactly when the action is {@link Action#SHOW}
 */
public record WaitingDecision(Action action, WaitReason reason) {

    /** The five things that can happen to somebody sitting on a black screen. */
    public enum Action {

        /** Nothing to do. Either they are already looking at the right title, or they are not held. */
        IDLE,

        /** Send {@code limbo} a {@code WAIT} carrying {@link WaitingDecision#reason()}. */
        SHOW,

        /**
         * Disconnect them: the pack offer went out and the client never answered it at all, for
         * longer than {@code pack.yml#apply-timeout-seconds}.
         */
        TIMED_OUT,

        /** Hand them to the router. Everything that had to be true is true, {@code READY} included. */
        RELEASE,

        /**
         * Hand them to the router <b>without</b> {@code limbo}'s {@code READY}, because it never
         * came and the grace period is over.
         *
         * <p>Distinct from {@link #RELEASE} so that the caller can say so out loud. A network where
         * this is the normal case is a network whose backend-to-proxy channel is broken, and the
         * only difference a player would notice is that nobody told them - which is how this became
         * a deadlock nobody could see in the first place.
         */
        RELEASE_UNCONFIRMED
    }

    public WaitingDecision {
        Objects.requireNonNull(action, "action");
        if ((action == Action.SHOW) != (reason != null)) {
            throw new IllegalArgumentException(
                    "SHOW is the only decision with a reason, got " + action + " / " + reason);
        }
    }

    /** @return the decision to do nothing */
    public static WaitingDecision idle() {
        return new WaitingDecision(Action.IDLE, null);
    }

    /**
     * @param reason the title the waiting room should now be showing
     * @return the decision to send it
     */
    public static WaitingDecision show(final WaitReason reason) {
        return new WaitingDecision(Action.SHOW, Objects.requireNonNull(reason, "reason"));
    }

    /** @return the decision to disconnect a player whose client never answered the pack offer */
    public static WaitingDecision timedOut() {
        return new WaitingDecision(Action.TIMED_OUT, null);
    }

    /**
     * @param confirmed whether {@code limbo} confirmed the arrival with a {@code READY}
     * @return the decision to connect the player onward
     */
    public static WaitingDecision release(final boolean confirmed) {
        return new WaitingDecision(confirmed ? Action.RELEASE : Action.RELEASE_UNCONFIRMED, null);
    }
}
