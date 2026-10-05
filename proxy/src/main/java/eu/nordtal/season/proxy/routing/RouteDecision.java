package eu.nordtal.season.proxy.routing;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** What should happen to one player, as a value {@code PlayerRouter} carries out and a test can assert. */
public record RouteDecision(Action action, @Nullable String server) {

    /** Everything that can happen to a player, plus "nothing". */
    public enum Action {

        /** Connect the player to {@link RouteDecision#server()}. */
        CONNECT,

        /** Leave the player where they are: an admin during {@code MAINTENANCE}. */
        STAY,

        /** No Discord account is linked any more. Disconnect. */
        REFUSE_UNLINKED,

        /** The linked Discord account has left the guild or is banned. Disconnect. */
        REFUSE_NOT_MEMBER,

        /** {@code SMP} without an active access period. Disconnect, never a redirect to {@code limbo}. */
        REFUSE_NO_ACCESS,

        /** {@code MAINTENANCE} and no {@code limbo} to hold the player in, so the maintenance screen disconnects. */
        REFUSE_MAINTENANCE_UNAVAILABLE,

        /** The phase's backend is not registered and the phase is not {@code MAINTENANCE}: a config error. */
        REFUSE_NO_SERVER,

        /**
         * The network was switched back to {@code PRE_LAUNCH} while this player was on it, unbought.
         *
         * Only reachable from the phase-change re-route; disconnects with the gate's countdown screen.
         */
        REFUSE_PRE_LAUNCH_BUY,

        /** The same, for a player who has already bought a period. */
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

    /** Whether this decision moves the player anywhere. */
    public boolean connects() {
        return action == Action.CONNECT;
    }

    /** Whether this decision ends the player's session. */
    public boolean refuses() {
        return action != Action.CONNECT && action != Action.STAY;
    }
}
