package eu.nordtal.s2.proxy.pack;

import eu.nordtal.s2.limboprotocol.WaitReason;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What to do with one player in the waiting room, right now.
 *
 * @param reason the title to show, non-{@code null} exactly when the action is {@link Action#SHOW}
 */
public record WaitingDecision(Action action, @Nullable WaitReason reason) {

    public enum Action {

        /** Nothing to do: they see the right title already, or they are not held. */
        IDLE,

        /** Send {@code limbo} a {@code WAIT} carrying {@link WaitingDecision#reason()}. */
        SHOW,

        /** Disconnect them: the client never answered the pack offer within {@code pack#apply-timeout-seconds}. */
        TIMED_OUT,

        /** Hand them to the router; everything had to be true is true, {@code READY} included. */
        RELEASE,

        /**
         * Hand them to the router without {@code limbo}'s {@code READY}, after the grace period; the caller logs it.
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
