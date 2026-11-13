package eu.nordtal.season.proxy.routing;

import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.database.access.AccessState;
import eu.nordtal.season.proxy.NetworkServers;
import eu.nordtal.season.proxy.ProxyRole;
import eu.nordtal.season.proxy.gate.GateOutcome;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/** Decides the server of a player from the {@link AccessState} and the registered servers. */
public final class SeasonRouting {

    private final NetworkServers servers;
    private final ProxyRole role;

    public SeasonRouting(final NetworkServers servers) {
        this(servers, ProxyRole.LIVE);
    }

    public SeasonRouting(final NetworkServers servers, final ProxyRole role) {
        this.servers = Objects.requireNonNull(servers, "servers");
        this.role = Objects.requireNonNull(role, "role");
    }

    /**
     * Returns the decision for a connected player by the gate's order. An admin stays where the admin is.
     *
     * @param available the names of the registered servers
     */
    public RouteDecision decide(final AccessState state, final Set<String> available, final Instant now) {
        Objects.requireNonNull(available, "available");
        final RouteDecision refusal = refusalOf(GateOutcome.of(state, now));
        if (refusal != null) {
            return refusal;
        }
        if (state.admin()) {
            return RouteDecision.of(RouteDecision.Action.STAY);
        }
        if (state.waits(now)) {
            final String room = waitingRoomAmong(available);
            return room == null
                    ? RouteDecision.of(RouteDecision.Action.REFUSE_MAINTENANCE_UNAVAILABLE)
                    : RouteDecision.connectTo(room);
        }
        return toModeServer(state.season().mode(), available);
    }

    /**
     * Returns the decision for an admitted login: connect to the waiting room.
     * Without a waiting room only an admin connects to the server of the active mode.
     */
    public RouteDecision decideInitial(final SeasonState season, final boolean admin, final Set<String> available) {
        return decideInitial(season, admin, available, server -> false);
    }

    /**
     * Returns the decision for an admitted login, avoiding a waiting room that an update run stops.
     * It is {@link #decideInitial(SeasonState, boolean, Set)} while the other room is not registered.
     *
     * @param stopping whether an update run stops the server
     */
    public RouteDecision decideInitial(
            final SeasonState season,
            final boolean admin,
            final Set<String> available,
            final Predicate<String> stopping) {
        Objects.requireNonNull(season, "season");
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(stopping, "stopping");

        final Set<String> spared =
                available.stream().filter(server -> !stopping.test(server)).collect(Collectors.toSet());
        final String sparedRoom = waitingRoomAmong(spared);
        final String room = sparedRoom != null ? sparedRoom : waitingRoomAmong(available);
        if (room != null) {
            return RouteDecision.connectTo(room);
        }
        if (admin) {
            final String destination = servers.forMode(season.mode());
            if (available.contains(destination)) {
                return RouteDecision.connectTo(destination);
            }
        }
        return RouteDecision.of(
                season.maintenance()
                        ? RouteDecision.Action.REFUSE_MAINTENANCE_UNAVAILABLE
                        : RouteDecision.Action.REFUSE_NO_SERVER);
    }

    /**
     * Returns the decision for a player who leaves the waiting room, through the gate.
     * A player whom maintenance holds gets {@code STAY}; an admin goes to the server of the active mode.
     */
    public RouteDecision decideRelease(final AccessState state, final Set<String> available, final Instant now) {
        Objects.requireNonNull(available, "available");
        final RouteDecision refusal = refusalOf(GateOutcome.of(state, now));
        if (refusal != null) {
            return refusal;
        }
        if (state.waits(now)) {
            return RouteDecision.of(RouteDecision.Action.STAY);
        }
        return toModeServer(state.season().mode(), available);
    }

    /**
     * Returns the release decision while the access of the player cannot be read.
     * The gate admitted the player at login, so only maintenance and the active mode count.
     */
    public RouteDecision decideRemembered(final SeasonState season, final boolean admin, final Set<String> available) {
        Objects.requireNonNull(season, "season");
        Objects.requireNonNull(available, "available");
        if (season.maintenance() && !admin) {
            return RouteDecision.of(RouteDecision.Action.STAY);
        }
        return toModeServer(season.mode(), available);
    }

    /** Returns the refusal of the outcome, {@code null} for {@link GateOutcome#ALLOW}. */
    static @Nullable RouteDecision refusalOf(final GateOutcome outcome) {
        final RouteDecision.@Nullable Action action = switch (outcome) {
            case ALLOW -> null;
            case NOT_LINKED -> RouteDecision.Action.REFUSE_UNLINKED;
            case NOT_MEMBER -> RouteDecision.Action.REFUSE_NOT_MEMBER;
            case NO_ACCESS -> RouteDecision.Action.REFUSE_NO_ACCESS;
            case CLOSED -> RouteDecision.Action.REFUSE_CLOSED;
            case COUNTDOWN_BUY -> RouteDecision.Action.REFUSE_COUNTDOWN_BUY;
            case COUNTDOWN_READY -> RouteDecision.Action.REFUSE_COUNTDOWN_READY;
            case FULL, TROUBLE -> throw new IllegalStateException("not an access table outcome: " + outcome);
        };
        return action == null ? null : RouteDecision.of(action);
    }

    private RouteDecision toModeServer(final SeasonMode mode, final Set<String> available) {
        final String destination = servers.forMode(mode);
        return available.contains(destination)
                ? RouteDecision.connectTo(destination)
                : RouteDecision.of(RouteDecision.Action.REFUSE_NO_SERVER);
    }

    public NetworkServers servers() {
        return servers;
    }

    /** Returns {@code limbo} if registered, otherwise {@code limbo-standby} if registered, otherwise {@code null}. */
    private @Nullable String waitingRoomAmong(final Set<String> available) {
        return waitingRoomAmong(available, servers, role);
    }

    /** Returns the waiting room for the inputs. The standby proxy prefers {@code limbo-standby}. */
    static @Nullable String waitingRoomAmong(
            final Set<String> available, final NetworkServers servers, final ProxyRole role) {
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
