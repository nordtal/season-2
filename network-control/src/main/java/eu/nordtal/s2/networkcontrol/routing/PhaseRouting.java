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
 * "Route them to limbo" means "connect them to the backend named by
 * {@code gate.yml#server-limbo}", and that server may not be registered on this proxy at all. This
 * class does not pretend otherwise - it takes the set of names the proxy knows and produces
 * {@link RouteDecision.Action#REFUSE_MAINTENANCE_UNAVAILABLE} when the waiting room is missing
 * during maintenance, and {@link RouteDecision.Action#REFUSE_NO_SERVER} when it is missing on any
 * other login. Since the pack station exists (2026-09-01) that second case refuses a login the old
 * code would have let through on {@code velocity.toml}'s {@code try} list - see
 * {@link #decideInitial(SeasonPhase, boolean, Set)} for why letting them in without the pack is
 * the worse of the two failures.
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
     * Where an admitted login goes <b>first</b>, which since the pack station exists is the waiting
     * room and nothing else.
     * <p>
     * docs/architecture.md#the-login-path-end-to-end: "every login lands on {@code limbo} first,
     * whatever the phase". {@link #decideAdmitted(SeasonPhase, boolean, Set)} answers the other
     * question - where a player belongs once they are <em>past</em> the waiting room - and the two
     * are deliberately separate methods rather than one with a flag, because they disagree in every
     * phase but {@code MAINTENANCE} and confusing them would either skip the pack or send a player
     * to the waiting room they just left.
     * </p>
     *
     * <h2>A missing waiting room refuses the login</h2>
     * If {@code gate.yml#server-limbo} names a server this proxy does not have, the player is
     * disconnected rather than sent straight to the phase's backend. That is a deliberate
     * fail-closed, and it is a change from the behaviour before the pack station existed, where a
     * non-maintenance login simply followed {@code velocity.toml}'s own {@code try} list. The
     * fallback would mean letting everybody onto the network <em>without the resource pack</em> -
     * silently, because nothing about a plain-looking tab list announces that every glyph in the
     * HUD, the nametags and the boards is missing. "Nobody can join" is a fault that reports
     * itself within seconds; "everybody joined without the pack" is one nobody notices until an
     * event day.
     *
     * @param phase     the phase the network is in
     * @param admin     whether the player carries {@code discord_user.admin}
     * @param available the names of the servers this proxy has registered
     * @return {@code CONNECT limbo}, {@code STAY} for an admin during maintenance, or the refusal
     *         that fits the phase
     */
    public RouteDecision decideInitial(final SeasonPhase phase, final boolean admin,
                                       final Set<String> available) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(available, "available");

        if (phase == SeasonPhase.MAINTENANCE && admin) {
            // Unchanged from before the pack station: an admin during maintenance is the one player
            // the phase does not move, and the login path is where that starts. The cost is that
            // they are also the one player who is not offered the pack, which is the right way
            // round - an admin joining a network under maintenance is there to look at the servers
            // being worked on, and is the person best placed to fix their own pack.
            return RouteDecision.of(RouteDecision.Action.STAY);
        }

        if (available.contains(servers.limbo())) {
            return RouteDecision.connectTo(servers.limbo());
        }

        return RouteDecision.of(phase == SeasonPhase.MAINTENANCE
                ? RouteDecision.Action.REFUSE_MAINTENANCE_UNAVAILABLE
                : RouteDecision.Action.REFUSE_NO_SERVER);
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
