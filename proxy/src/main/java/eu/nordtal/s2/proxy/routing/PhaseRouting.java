package eu.nordtal.s2.proxy.routing;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.proxy.gate.GateOutcome;

import java.util.Objects;
import java.util.Set;

/**
 * Where a player belongs, as a total function of one {@link AccessState} and the set of servers this
 * proxy actually has.
 *
 * <p>Built on {@link GateOutcome} so that admission and destination cannot drift: the gate decides
 * whether a player is allowed and this class only decides where an allowed one goes.</p>
 *
 * <p>A server named in {@code gate.yml} may not be registered on this proxy at all, which produces
 * {@link RouteDecision.Action#REFUSE_MAINTENANCE_UNAVAILABLE} for a missing waiting room during
 * maintenance and {@link RouteDecision.Action#REFUSE_NO_SERVER} otherwise.</p>
 *
 * <p>What it cannot see is a server that <em>is</em> registered and is <em>down</em>; that failure
 * surfaces when the connection is attempted and is handled by the caller.</p>
 */
public final class PhaseRouting {

    private final PhaseServers servers;
    private final ProxyRole role;

    public PhaseRouting(final PhaseServers servers) {
        this(servers, ProxyRole.LIVE);
    }

    /**
     * @param role which of the two proxies this process is - it changes exactly one thing here,
     *             {@link #waitingRoomAmong}, and see that method for why that one thing is not
     *             optional
     */
    public PhaseRouting(final PhaseServers servers, final ProxyRole role) {
        this.servers = Objects.requireNonNull(servers, "servers");
        this.role = Objects.requireNonNull(role, "role");
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
                // A switch to SMP disconnects a player with no active access, with the login
                // gate's own message. Never a redirect to limbo: limbo is for waiting on something
                // that ends, and this does not end by waiting.
                return RouteDecision.of(RouteDecision.Action.REFUSE_NO_ACCESS);
            case PRE_LAUNCH_BUY:
                // The network went back to PRE_LAUNCH under a player already on it, so they get
                // the screens the gate would now show them.
                return RouteDecision.of(RouteDecision.Action.REFUSE_PRE_LAUNCH_BUY);
            case PRE_LAUNCH_READY:
                return RouteDecision.of(RouteDecision.Action.REFUSE_PRE_LAUNCH_READY);
            case ALLOW:
                break;
            default:
                throw new IllegalStateException("unhandled gate outcome: " + outcome);
        }

        return decideAdmitted(state.phase(), state.admin(), available);
    }

    /**
     * Where an admitted login goes <b>first</b>: every login lands on {@code limbo}, whatever the
     * phase.
     * <p>
     * Separate from {@link #decideAdmitted(SeasonPhase, boolean, Set)}, which answers where a player
     * belongs once they are <em>past</em> the waiting room. The two disagree in every phase but
     * {@code MAINTENANCE}, and confusing them would either skip the pack or send a player back into
     * the room they just left.
     * </p>
     *
     * <p>A missing waiting room refuses the login rather than sending the player straight to the
     * phase's backend: "nobody can join" reports itself within seconds, while "everybody joined
     * without the resource pack" is not noticed until an event day.</p>
     *
     * @param phase     the phase the network is in
     * @param admin     whether the player carries {@code discord_user.admin}
     * @param available the names of the servers this proxy has registered
     * @return {@code CONNECT limbo} whenever there is one; for an admin without one, the server the
     *         room would have released them onto; otherwise the refusal that fits the phase
     */
    public RouteDecision decideInitial(final SeasonPhase phase, final boolean admin,
                                       final Set<String> available) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(available, "available");

        // THE LIVE ROOM FIRST, THEN THE STANDBY (season-2-ops/120). The order is the whole of the
        // rule: `limbo` is the waiting room, and `limbo-standby` is only ever the answer because
        // `limbo` is not registered on this proxy right now - which, during a run that includes the
        // waiting room, is exactly what it means for the room to be stopped. Preferring the standby
        // while both are up would split arrivals across two rooms for no reason.
        final String room = waitingRoomAmong(available);
        if (room != null) {
            // Everybody, admins included. STAY would leave the server choice to Velocity, whose
            // `try` list is `limbo` anyway - so the admin would be released from the waiting room
            // into the waiting room, which is a black screen with a stale title and no timeout.
            // Where they go instead is PhaseServers#forAdmitted's job.
            return RouteDecision.connectTo(room);
        }

        if (admin) {
            // No waiting room at all. The refusal below is right for everybody else, and wrong for
            // the one person who could fix it: a missing limbo that locked admins out could never
            // be repaired. They go straight to where the room would have released them, without the
            // pack, and PlayerRouter says so in the log.
            final String destination = servers.forAdmitted(phase, true);
            if (available.contains(destination)) {
                return RouteDecision.connectTo(destination);
            }
        }

        return RouteDecision.of(phase == SeasonPhase.MAINTENANCE
                ? RouteDecision.Action.REFUSE_MAINTENANCE_UNAVAILABLE
                : RouteDecision.Action.REFUSE_NO_SERVER);
    }

    /**
     * The destination half on its own, for a player admission has already been settled for.
     * <p>
     * At {@code PlayerChooseInitialServerEvent} the proxy has only what the login query put in the
     * roster, and re-reading the database there would be a second round trip on a login path pinned
     * to exactly one. These two facts are all that branch needs.
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

        if ((phase == SeasonPhase.MAINTENANCE || phase == SeasonPhase.PRE_LAUNCH) && admin) {
            // An admin is the one player maintenance does not move: picking a server on a phase
            // change would evict them from whatever they were inspecting. An admin still IN the
            // waiting room is not this method's business - the station uses decideRelease.
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

    /**
     * Where the waiting room lets a player out to.
     * <p>
     * Not {@link #decideAdmitted}, whose {@code STAY} is right for an admin already standing on a
     * backend: a player leaving the waiting room is standing on {@code limbo}, where staying is a
     * stale-title black screen. So this never says {@code STAY}.
     * </p>
     *
     * @param phase     the phase the network is in
     * @param admin     whether the player carries {@code discord_user.admin}
     * @param available the names of the servers this proxy has registered
     * @return {@code CONNECT} to the phase's backend - the SMP for an admin while the network is
     *         closed - or the refusal for a backend this proxy does not have
     */
    public RouteDecision decideRelease(final SeasonPhase phase, final boolean admin,
                                       final Set<String> available) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(available, "available");

        final String destination = servers.forAdmitted(phase, admin);
        if (available.contains(destination)) {
            return RouteDecision.connectTo(destination);
        }
        return RouteDecision.of(phase == SeasonPhase.MAINTENANCE
                ? RouteDecision.Action.REFUSE_MAINTENANCE_UNAVAILABLE
                : RouteDecision.Action.REFUSE_NO_SERVER);
    }

    public PhaseServers servers() {
        return servers;
    }

    /**
     * Which waiting room this proxy can actually put somebody in, out of the two it knows.
     *
     * <p>Null rather than an empty {@code Optional} because the one caller asks once and branches;
     * and it returns a NAME rather than a boolean so the caller cannot accidentally connect to the
     * one it did not check for.</p>
     *
     * @param available the backends registered on this proxy
     * @return {@link PhaseServers#limbo()} if it is there, else
     *         {@link PhaseServers#limboStandby()} if <em>that</em> is, else {@code null}
     */
    private String waitingRoomAmong(final Set<String> available) {
        return waitingRoomAmong(available, servers, role);
    }

    /**
     * The same, as a function of its three inputs - the split the rest of this plugin uses, so the
     * one rule that reverses can be asserted rather than read.
     *
     * <h2>Why the standby's preference is the other way round, and why that is not symmetry</h2>
     * On the live proxy the rule reads "the room, unless it is being updated". On the standby it
     * reads "the standby room, always" - and the difference is not tidiness, it is the whole reason
     * a proxy swap is safe to build without the measurement the ticket could not make.
     *
     * <p>A proxy swap does not touch the backends: {@code limbo} is up and registered throughout,
     * so a standby using the live rule would put every arrival there. But the players arriving are
     * the players who <em>just left</em> the live proxy, and some of them were standing in
     * {@code limbo} when they did. That is the same UUID leaving and rejoining one Paper server
     * within a second - the "You are already logged in" the ticket names as the thing to reproduce
     * before building anything. Sending them to the other room means nobody rejoins a backend they
     * were on, so the question cannot reach this code at all.</p>
     *
     * @param available the backends registered on this proxy
     * @param servers   the backend names
     * @param role      which proxy this is
     * @return a room to put somebody in, or {@code null} if this proxy has neither
     */
    static String waitingRoomAmong(final Set<String> available, final PhaseServers servers,
                                   final ProxyRole role) {
        final String first = role.isStandby() ? servers.limboStandby() : servers.limbo();
        final String second = role.isStandby() ? servers.limbo() : servers.limboStandby();
        if (available.contains(first)) {
            return first;
        }
        if (available.contains(second)) {
            return second;
        }
        return null;
    }
}
