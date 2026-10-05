package eu.nordtal.season.proxy.routing;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.database.access.AccessState;
import eu.nordtal.season.proxy.PhaseServers;
import eu.nordtal.season.proxy.ProxyRole;
import eu.nordtal.season.proxy.gate.GateOutcome;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Where a player belongs, as a total function of one {@link AccessState} and the servers this proxy has.
 *
 * A registered server that is down is not visible here; the caller handles the failed connection.
 */
public final class PhaseRouting {

    private final PhaseServers servers;
    private final ProxyRole role;

    public PhaseRouting(final PhaseServers servers) {
        this(servers, ProxyRole.LIVE);
    }

    public PhaseRouting(final PhaseServers servers, final ProxyRole role) {
        this.servers = Objects.requireNonNull(servers, "servers");
        this.role = Objects.requireNonNull(role, "role");
    }

    /**
     * Decides what happens to one player, in the phase {@code state} carries.
     *
     * @param available the names from {@code ProxyServer.getAllServers()}
     * @return what to do, never {@code null}
     */
    public RouteDecision decide(final AccessState state, final Set<String> available) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(available, "available");

        final GateOutcome outcome = GateOutcome.of(state);
        return switch (outcome) {
            case NOT_LINKED -> RouteDecision.of(RouteDecision.Action.REFUSE_UNLINKED);
            case NOT_MEMBER -> RouteDecision.of(RouteDecision.Action.REFUSE_NOT_MEMBER);
            // Never a redirect to limbo: limbo is for waiting on something that ends.
            case NO_ACCESS -> RouteDecision.of(RouteDecision.Action.REFUSE_NO_ACCESS);
            // The network went back to PRE_LAUNCH under a player on it; they get the gate's own screens.
            case PRE_LAUNCH_BUY -> RouteDecision.of(RouteDecision.Action.REFUSE_PRE_LAUNCH_BUY);
            case PRE_LAUNCH_READY -> RouteDecision.of(RouteDecision.Action.REFUSE_PRE_LAUNCH_READY);
            case ALLOW -> decideAdmitted(state.phase(), state.admin(), available);
        };
    }

    /**
     * Where an admitted login goes first: {@code limbo}, whatever the phase.
     *
     * A missing waiting room refuses the login; only an admin goes straight to the phase's backend.
     */
    public RouteDecision decideInitial(final SeasonPhase phase, final boolean admin, final Set<String> available) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(available, "available");

        // The live room first: preferring the standby while both are up would split arrivals.
        final String room = waitingRoomAmong(available);
        if (room != null) {
            // Everybody, admins included: STAY would release them back into the room they just entered.
            return RouteDecision.connectTo(room);
        }

        if (admin) {
            // No waiting room at all: an admin locked out could never repair a missing limbo.
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

    /** Where a player past admission belongs, from the facts the roster already holds. */
    public RouteDecision decideAdmitted(final SeasonPhase phase, final boolean admin, final Set<String> available) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(available, "available");

        if ((phase == SeasonPhase.MAINTENANCE || phase == SeasonPhase.PRE_LAUNCH) && admin) {
            // Maintenance does not move an admin; one still in the waiting room uses decideRelease.
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

    /** Where the waiting room lets a player out to; never {@code STAY}, which would leave them on a black screen. */
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
     * Returns {@code limbo} if registered, else {@code limbo-standby} if registered, else {@code null}.
     *
     * @param available the backends registered on this proxy
     */
    private @Nullable String waitingRoomAmong(final Set<String> available) {
        return waitingRoomAmong(available, servers, role);
    }

    /**
     * The same, as a function of its inputs; the standby prefers the standby room.
     *
     * Otherwise a player parked out of {@code limbo} would rejoin it within a second and collide with themselves.
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
