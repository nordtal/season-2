package eu.nordtal.s2.networkcontrol.routing;

import java.util.Objects;

/**
 * What should happen to one player, given the phase the network is in and what the database says
 * about them. Produced by {@link PhaseRouting#decide} and carried out by
 * {@code PlayerRouter}.
 *
 * <p>It is a value rather than a method call on a Velocity {@code Player} so that the rules can be
 * asserted in memory. Nothing in this repository's test suite can drive a real proxy, so the part
 * worth testing is separated from the part that cannot be.
 */
public record RouteDecision(Action action, String server) {

    /** The five things that can happen, plus "nothing". */
    public enum Action {

        /** Connect the player to {@link RouteDecision#server()}. */
        CONNECT,

        /**
         * Leave the player exactly where they are. Today this is the admin during
         * {@code MAINTENANCE}: docs/season-phases.md says admins "get in normally", and normally
         * means the servers being worked on, not the waiting room.
         */
        STAY,

        /** No Discord account is linked any more. Disconnect. */
        REFUSE_UNLINKED,

        /** The linked Discord account has left the guild or is banned. Disconnect. */
        REFUSE_NOT_MEMBER,

        /**
         * {@code SMP} without an active access period. Disconnect - <b>never</b> a redirect to
         * {@code limbo}, decided 2026-08-31 (docs/season-phases.md#routing): "limbo is for waiting
         * on something that ends, and 'you have not bought access' does not end by waiting".
         */
        REFUSE_NO_ACCESS,

        /**
         * The network is in {@code MAINTENANCE} and this proxy has no {@code limbo} server to hold
         * the player in. Disconnect with the maintenance screen - the "disconnect" half of the
         * either/or docs/season-phases.md used to leave open, used for exactly the case where the
         * "hold in limbo" half is impossible.
         */
        REFUSE_MAINTENANCE_UNAVAILABLE,

        /**
         * The phase's backend is not registered on this proxy and the phase is not
         * {@code MAINTENANCE}. A configuration error; disconnect rather than leave the player
         * somewhere the phase says they should not be.
         */
        REFUSE_NO_SERVER
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

    /** @return whether this decision moves the player anywhere */
    public boolean connects() {
        return action == Action.CONNECT;
    }

    /** @return whether this decision ends the player's session */
    public boolean refuses() {
        return action != Action.CONNECT && action != Action.STAY;
    }
}
