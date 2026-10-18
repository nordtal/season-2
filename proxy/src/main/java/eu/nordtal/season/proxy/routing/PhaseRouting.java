package eu.nordtal.season.proxy.routing;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.database.access.AccessState;
import eu.nordtal.season.proxy.PhaseServers;
import eu.nordtal.season.proxy.ProxyRole;
import eu.nordtal.season.proxy.gate.GateOutcome;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Decides the server of a player from the {@link AccessState} and the registered servers. */
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
     * Returns the decision for the player in the phase of the state.
     *
     * @param available the names of the registered servers
     */
    public RouteDecision decide(final AccessState state, final Set<String> available) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(available, "available");

        final GateOutcome outcome = GateOutcome.of(state);
        return switch (outcome) {
            case NOT_LINKED -> RouteDecision.of(RouteDecision.Action.REFUSE_UNLINKED);
            case NOT_MEMBER -> RouteDecision.of(RouteDecision.Action.REFUSE_NOT_MEMBER);
            case NO_ACCESS -> RouteDecision.of(RouteDecision.Action.REFUSE_NO_ACCESS);
            case PRE_LAUNCH_BUY -> RouteDecision.of(RouteDecision.Action.REFUSE_PRE_LAUNCH_BUY);
            case PRE_LAUNCH_READY -> RouteDecision.of(RouteDecision.Action.REFUSE_PRE_LAUNCH_READY);
            case ALLOW -> decideAdmitted(state.phase(), state.admin(), available);
            case FULL, TROUBLE -> throw new IllegalStateException("not an access table outcome: " + outcome);
        };
    }

    /**
     * Returns the decision for an admitted login: connect to {@code limbo}. Without a waiting room only an admin
     * connects to the backend of the phase.
     */
    public RouteDecision decideInitial(final SeasonPhase phase, final boolean admin, final Set<String> available) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(available, "available");

        final String room = waitingRoomAmong(available);
        if (room != null) {
            return RouteDecision.connectTo(room);
        }

        if (admin) {
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

    /** Returns the decision for a player who is already admitted. */
    public RouteDecision decideAdmitted(final SeasonPhase phase, final boolean admin, final Set<String> available) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(available, "available");

        if ((phase == SeasonPhase.MAINTENANCE || phase == SeasonPhase.PRE_LAUNCH) && admin) {
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

    /** Returns the decision for a player who leaves the waiting room. It is never {@code STAY}. */
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

    /** Returns {@code limbo} if registered, otherwise {@code limbo-standby} if registered, otherwise {@code null}. */
    private @Nullable String waitingRoomAmong(final Set<String> available) {
        return waitingRoomAmong(available, servers, role);
    }

    /** Returns the waiting room for the inputs. The standby proxy prefers {@code limbo-standby}. */
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
