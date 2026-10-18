package eu.nordtal.season.proxy.pack;

import eu.nordtal.season.limboprotocol.WaitReason;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The action for a player in the waiting room.
 *
 * @param reason the reason to show, not {@code null} exactly for {@link Action#SHOW}
 */
public record WaitingDecision(Action action, @Nullable WaitReason reason) {

    public enum Action {

        /** Do nothing. */
        IDLE,

        /** Send {@code limbo} a {@code WAIT} with {@link WaitingDecision#reason()}. */
        SHOW,

        /**
         * Disconnect the player. The client did not answer the pack offer within {@code pack#apply-timeout-seconds}.
         */
        TIMED_OUT,

        /** Send the player to the router. */
        RELEASE,

        /** Send the player to the router without {@code READY} after the grace period. */
        RELEASE_UNCONFIRMED
    }

    public WaitingDecision {
        Objects.requireNonNull(action, "action");
        if ((action == Action.SHOW) != (reason != null)) {
            throw new IllegalArgumentException(
                    "SHOW is the only decision with a reason, got " + action + " / " + reason);
        }
    }

    public static WaitingDecision idle() {
        return new WaitingDecision(Action.IDLE, null);
    }

    public static WaitingDecision show(final WaitReason reason) {
        return new WaitingDecision(Action.SHOW, Objects.requireNonNull(reason, "reason"));
    }

    public static WaitingDecision timedOut() {
        return new WaitingDecision(Action.TIMED_OUT, null);
    }

    public static WaitingDecision release(final boolean confirmed) {
        return new WaitingDecision(confirmed ? Action.RELEASE : Action.RELEASE_UNCONFIRMED, null);
    }
}
