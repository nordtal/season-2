package eu.nordtal.season.proxy.routing;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** The action for a player, which {@code PlayerRouter} executes. */
public record RouteDecision(Action action, @Nullable String server) {

    /** The actions. */
    public enum Action {

        /** Connect the player to {@link RouteDecision#server()}. */
        CONNECT,

        /** Keep the player on the server. */
        STAY,

        /** Disconnect the player. No Discord account is linked. */
        REFUSE_UNLINKED,

        /** Disconnect the player. The Discord account has left the guild or is banned. */
        REFUSE_NOT_MEMBER,

        /** Disconnect the player. The phase is {@code SMP} and no period is running. */
        REFUSE_NO_ACCESS,

        /** Disconnect the player. The phase is {@code MAINTENANCE} and no {@code limbo} is registered. */
        REFUSE_MAINTENANCE_UNAVAILABLE,

        /** Disconnect the player. The backend of the phase is not registered. */
        REFUSE_NO_SERVER,

        /**
         * Disconnect the player with the countdown screen. The phase changed to {@code PRE_LAUNCH} and the player has
         * bought no period.
         */
        REFUSE_PRE_LAUNCH_BUY,

        /**
         * Disconnect the player with the countdown screen. The phase changed to {@code PRE_LAUNCH} and the player has
         * bought a period.
         */
        REFUSE_PRE_LAUNCH_READY
    }

    public RouteDecision {
        Objects.requireNonNull(action, "action");
        if ((action == Action.CONNECT) != (server != null)) {
            throw new IllegalArgumentException(
                    "CONNECT is the only action with a server, got " + action + " / " + server);
        }
    }

    static RouteDecision connectTo(final String server) {
        return new RouteDecision(Action.CONNECT, Objects.requireNonNull(server, "server"));
    }

    static RouteDecision of(final Action action) {
        return new RouteDecision(action, null);
    }

    /** Returns whether the decision connects the player. */
    public boolean connects() {
        return action == Action.CONNECT;
    }

    /** Returns whether the decision disconnects the player. */
    public boolean refuses() {
        return action != Action.CONNECT && action != Action.STAY;
    }
}
