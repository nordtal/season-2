package eu.nordtal.s2.networkcontrol.routing;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.networkcontrol.gate.GateOutcome;

import java.util.Objects;
import java.util.Set;

/**
 * Where a player belongs, as a total function of one {@link AccessState} and the set of servers this
 * proxy actually has. docs/season-phases.md's "where they land" column plus its routing section,
 * with the failure cases written down rather than left undefined.
 *
 * <h2>Why it is built on {@link GateOutcome}</h2>
 * Admission and destination have to agree. If routing re-derived "is this player allowed" it would
 * be a second copy of the phase table, and the two would drift - the failure that would show up as a
 * player let in at login and thrown out a moment later by the router, or the reverse. So the gate
 * decides admission and this class only decides where an admitted player goes.
 *
 * <h2>The limbo problem, stated honestly</h2>
 * The {@code limbo} module is a scaffold: a main class that logs on enable. "Route them to limbo"
 * therefore means "connect them to the backend named by {@code gate.yml#server-limbo}", and that
 * server may not be registered on this proxy at all. This class does not pretend otherwise - it
 * takes the set of names the proxy knows and produces
 * {@link RouteDecision.Action#REFUSE_MAINTENANCE_UNAVAILABLE} when the waiting room is missing,
 * which is the old pre-2026-08-31 behaviour used as a fallback rather than as the rule.
 *
 * <p>What it cannot see is a server that <em>is</em> registered and is <em>down</em>. A name in
 * {@code velocity.toml} pointing at a dead process looks identical here to a healthy one; that
 * failure surfaces when the connection is attempted, and is handled by the caller.
 */
public final class PhaseRouting {

    private final PhaseServers servers;

    public PhaseRouting(final PhaseServers servers) {
        this.servers = Objects.requireNonNull(servers, "servers");
    }

    /**
     * Decides what happens to one player.
     *
     * @param state     the answer to a fresh access query - its {@link AccessState#phase()} is the
     *                  phase this decision is made in, which is what makes a single round trip
     *                  enough for both halves of the answer
     * @param available the names of the servers this proxy has registered, from
     *                  {@code ProxyServer.getAllServers()}
     * @return what to do, never {@code null}
     */
    public RouteDecision decide(final AccessState state, final Set<String> available) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(available, "available");

        final GateOutcome outcome = GateOutcome.of(state);
        switch (outcome) {
            case NOT_LINKED:
                return RouteDecision.of(RouteDecision.Action.REFUSE_UNLINKED);
            case NOT_MEMBER:
                return RouteDecision.of(RouteDecision.Action.REFUSE_NOT_MEMBER);
            case NO_ACCESS:
                // docs/season-phases.md#routing, settled 2026-08-31: a switch to SMP disconnects a
                // player who has no active access, with the login gate's own message. It does NOT
                // push them to limbo, and this is the one branch that must never be "softened" into
                // a redirect - limbo is for waiting on something that ends.
                return RouteDecision.of(RouteDecision.Action.REFUSE_NO_ACCESS);
            case ALLOW:
                break;
            default:
                throw new IllegalStateException("unhandled gate outcome: " + outcome);
        }

        return decideAdmitted(state.phase(), state.admin(), available);
    }

    /**
     * The destination half on its own, for a player admission has already been settled for.
     * <p>
     * This exists because the proxy learns "where do they go" at two different moments with two
     * different amounts of information. On a phase change it has just re-read the whole
     * {@link AccessState} and calls {@link #decide(AccessState, Set)}. At
     * {@code PlayerChooseInitialServerEvent} it has only what the login query already put in the
     * roster, and re-reading the database there would be a second round trip on the login path -
     * which docs/season-phases.md pins to exactly one. These two facts are all that branch needs.
     * </p>
     *
     * @param phase     the phase the network is in
     * @param admin     whether the player carries {@code discord_user.admin}
     * @param available the names of the servers this proxy has registered
     * @return where to put them, never a refusal that has to do with admission
     */
    public RouteDecision decideAdmitted(final SeasonPhase phase, final boolean admin,
                                        final Set<String> available) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(available, "available");

        if (phase == SeasonPhase.MAINTENANCE && admin) {
            // "Admins get in normally." Normally means the servers being worked on, so an admin is
            // the one player maintenance does not move. Leaving them alone is also the only answer
            // that works on a phase change: there is no "where an admin belongs during maintenance"
            // to send them to, and picking one would evict them from whatever they were inspecting.
            return RouteDecision.of(RouteDecision.Action.STAY);
        }

        final String destination = servers.forPhase(phase);
        if (available.contains(destination)) {
            return RouteDecision.connectTo(destination);
        }

        return RouteDecision.of(phase == SeasonPhase.MAINTENANCE
                ? RouteDecision.Action.REFUSE_MAINTENANCE_UNAVAILABLE
                : RouteDecision.Action.REFUSE_NO_SERVER);
    }

    /** @return the phase-to-server table this router uses */
    public PhaseServers servers() {
        return servers;
    }
}
