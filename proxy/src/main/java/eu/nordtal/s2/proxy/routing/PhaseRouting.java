package eu.nordtal.s2.proxy.routing;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.proxy.PhaseServers;
import eu.nordtal.s2.proxy.ProxyRole;
import eu.nordtal.s2.proxy.gate.GateOutcome;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Where a player belongs, as a total function of one {@link AccessState} and the servers this proxy actually has.
 *
 * Built on {@link GateOutcome} so that admission and destination cannot drift: the gate decides
 * whether a player is allowed and this class only decides where an allowed one goes.
 *
 * A server named in {@code gate.yml} may not be registered on this proxy at all, which produces
 * {@link RouteDecision.Action#REFUSE_MAINTENANCE_UNAVAILABLE} for a missing waiting room during
 * maintenance and {@link RouteDecision.Action#REFUSE_NO_SERVER} otherwise.
 *
 * What it cannot see is a server that is registered and is down; that failure
 * surfaces when the connection is attempted and is handled by the caller.
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
        return switch (outcome) {
            case NOT_LINKED -> RouteDecision.of(RouteDecision.Action.REFUSE_UNLINKED);
            case NOT_MEMBER -> RouteDecision.of(RouteDecision.Action.REFUSE_NOT_MEMBER);
            // Never a redirect to limbo: limbo is for waiting on something that ends, and this does not.
            case NO_ACCESS -> RouteDecision.of(RouteDecision.Action.REFUSE_NO_ACCESS);
            // The network went back to PRE_LAUNCH under a player already on it; they get the gate's own screens.
            case PRE_LAUNCH_BUY -> RouteDecision.of(RouteDecision.Action.REFUSE_PRE_LAUNCH_BUY);
            case PRE_LAUNCH_READY -> RouteDecision.of(RouteDecision.Action.REFUSE_PRE_LAUNCH_READY);
            case ALLOW -> decideAdmitted(state.phase(), state.admin(), available);
        };
    }

    /**
     * Where an admitted login goes first: every login lands on {@code limbo}, whatever the phase.
     *
     * Separate from {@link #decideAdmitted(SeasonPhase, boolean, Set)}, which answers where a player
     * belongs once they are past the waiting room. The two disagree in every phase but
     * {@code MAINTENANCE}, and confusing them would either skip the pack or send a player back into
     * the room they just left.
     *
     * A missing waiting room refuses the login rather than sending the player straight to the
     * phase's backend: "nobody can join" reports itself within seconds, while "everybody joined
     * without the resource pack" is not noticed until an event day.
     *
     * @param phase     the phase the network is in
     * @param admin     whether the player carries {@code discord_user.admin}
     * @param available the names of the servers this proxy has registered
     * @return {@code CONNECT limbo} whenever there is one; for an admin without one, the server the
     *         room would have released them onto; otherwise the refusal that fits the phase
     */
    public RouteDecision decideInitial(final SeasonPhase phase, final boolean admin, final Set<String> available) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(available, "available");

        // The live room first, then the standby: preferring the standby while both are up would split arrivals.
        final String room = waitingRoomAmong(available);
        if (room != null) {
            // Everybody, admins included: STAY would release them back into the waiting room they just entered.
            return RouteDecision.connectTo(room);
        }

        if (admin) {
            // No waiting room at all: a missing limbo that locked admins out could never be repaired otherwise.
            final String destination = servers.forAdmitted(phase, true);
            if (available.contains(destination)) {
                return RouteDecision.connectTo(destination);
            }
        }

        return RouteDecision.of(
                phase == SeasonPhase.MAINTENANCE
                        ? RouteDecision.Action.REFUSE_MAINTENANCE_UNAVAILABLE
                        : RouteDecision.Action.REFUSE_NO_SERVER);
    }

    /**
     * The destination half on its own, for a player admission has already been settled for.
     *
     * At {@code PlayerChooseInitialServerEvent} the proxy has only what the login query put in the
     * roster, and re-reading the database there would be a second round trip on a login path pinned
     * to exactly one. These two facts are all that branch needs.
     *
     * @param phase     the phase the network is in
     * @param admin     whether the player carries {@code discord_user.admin}
     * @param available the names of the servers this proxy has registered
     * @return where to put them, never a refusal that has to do with admission
     */
    public RouteDecision decideAdmitted(final SeasonPhase phase, final boolean admin, final Set<String> available) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(available, "available");

        if ((phase == SeasonPhase.MAINTENANCE || phase == SeasonPhase.PRE_LAUNCH) && admin) {
            // An admin is the one player maintenance does not move; one still in the waiting room uses decideRelease.
            return RouteDecision.of(RouteDecision.Action.STAY);
        }

        final String destination = servers.forPhase(phase);
        if (available.contains(destination)) {
            return RouteDecision.connectTo(destination);
        }

        return RouteDecision.of(
                phase == SeasonPhase.MAINTENANCE
                        ? RouteDecision.Action.REFUSE_MAINTENANCE_UNAVAILABLE
                        : RouteDecision.Action.REFUSE_NO_SERVER);
    }

    /**
     * Where the waiting room lets a player out to.
     *
     * Not {@link #decideAdmitted}, whose {@code STAY} is right for an admin already standing on a
     * backend: a player leaving the waiting room is standing on {@code limbo}, where staying is a
     * stale-title black screen. So this never says {@code STAY}.
     *
     * @param phase     the phase the network is in
     * @param admin     whether the player carries {@code discord_user.admin}
     * @param available the names of the servers this proxy has registered
     * @return {@code CONNECT} to the phase's backend - the SMP for an admin while the network is
     *         closed - or the refusal for a backend this proxy does not have
     */
    public RouteDecision decideRelease(final SeasonPhase phase, final boolean admin, final Set<String> available) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(available, "available");

        final String destination = servers.forAdmitted(phase, admin);
        if (available.contains(destination)) {
            return RouteDecision.connectTo(destination);
        }
        return RouteDecision.of(
                phase == SeasonPhase.MAINTENANCE
                        ? RouteDecision.Action.REFUSE_MAINTENANCE_UNAVAILABLE
                        : RouteDecision.Action.REFUSE_NO_SERVER);
    }

    public PhaseServers servers() {
        return servers;
    }

    /**
     * Which waiting room this proxy can actually put somebody in, out of the two it knows.
     *
     * Null rather than an empty {@code Optional} because the one caller asks once and branches;
     * and it returns a name rather than a boolean so the caller cannot accidentally connect to the
     * one it did not check for.
     *
     * @param available the backends registered on this proxy
     * @return {@link PhaseServers#limbo()} if it is there, else
     *         {@link PhaseServers#limboStandby()} if that is, else {@code null}
     */
    private @Nullable String waitingRoomAmong(final Set<String> available) {
        return waitingRoomAmong(available, servers, role);
    }

    /**
     * The same, as a function of its three inputs - the split the rest of this plugin uses.
     *
     * So the one rule that reverses can be asserted rather than read.
     *
     * The standby's preference is the other way round, and that is not symmetry: on the live proxy
     * the rule reads "the room, unless it is being updated". On the standby it reads "the standby
     * room, always".
     *
     * A proxy swap does not touch the backends: {@code limbo} is up and registered throughout,
     * so a standby using the live rule would put every arrival there. But the players arriving are
     * the players who just left the live proxy, and some of them were standing in
     * {@code limbo} when they did. That is the same UUID leaving and rejoining one Paper server
     * within a second - an "already logged in" collision. Sending them to the other room means
     * nobody rejoins a backend they were on, so the question cannot reach this code at all.
     *
     * @param available the backends registered on this proxy
     * @param servers   the backend names
     * @param role      which proxy this is
     * @return a room to put somebody in, or {@code null} if this proxy has neither
     */
    static @Nullable String waitingRoomAmong(
            final Set<String> available, final PhaseServers servers, final ProxyRole role) {
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
