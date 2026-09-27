package eu.nordtal.s2.proxy.update;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.proxy.PhaseServers;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Moves the players off a backend an update is about to stop into the waiting room, at zero and not before.
 *
 * The waiting room's sweep brings them back; nobody moves when the run stops every waiting room.
 */
public final class Evacuation {

    private final ProxyServer proxy;
    private final Logger logger;
    private final UpdateDirectory updates;
    private final PhaseServers servers;

    /** Who to tell when their server takes them back. */
    private final Homecoming homecoming;
    /** The backends a run has, or is about to have, stopped; replaced wholesale since other threads read it. */
    private volatile Set<String> moving = Set.of();

    /** The backends somebody deliberately holds down; kept apart since the screen promises no return. */
    private volatile Set<String> held = Set.of();

    /** Whether the last pass refused the evacuation, so the warning is logged once per run. */
    private volatile boolean warnedAboutWaitingRoom;

    public Evacuation(
            final ProxyServer proxy,
            final Logger logger,
            final UpdateDirectory updates,
            final PhaseServers servers,
            final Homecoming homecoming) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.updates = Objects.requireNonNull(updates, "updates");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.homecoming = Objects.requireNonNull(homecoming, "homecoming");
    }

    /** Returns whether an update run has this backend stopped or is stopping it, which shows {@code UPDATE}. */
    public boolean isMoving(final String server) {
        return server != null && moving.contains(server);
    }

    /**
     * Returns whether a run has anything stopped or about to be, which puts {@code OnlineWriter} on its fast cadence.
     */
    public boolean isAnyMoving() {
        return !moving.isEmpty();
    }

    /** Returns whether somebody holds this backend down on purpose, which shows {@code HELD}. */
    public boolean isHeld(final String server) {
        return server != null && held.contains(server);
    }

    /**
     * One pass, beside {@code RestartWatch} on the same interval and notification.
     *
     * Never throws: Velocity stops running a task that does.
     */
    public void check() {
        final Set<String> next;
        try {
            // Read first: a pass with no run to report still needs the holds.
            held = heldServices(updates.holds());
            next = imminent();
        } catch (final RuntimeException failure) {
            // The last pass's set stands.
            logger.warn("Could not read the update row; nobody was moved this pass", failure);
            return;
        }

        if (next.isEmpty()) {
            moving = Set.of();
            warnedAboutWaitingRoom = false;
            return;
        }

        // Ordinarily the waiting room itself, or limbo-standby if the run stops that too.
        final String room = roomFor(
                next,
                servers.limbo(),
                servers.limboStandby(),
                name -> proxy.getServer(name).isPresent());
        if (room == null) {
            // The run stops the waiting room and no standby is registered; logged every pass.
            if (!warnedAboutWaitingRoom) {
                warnedAboutWaitingRoom = true;
                logger.warn(
                        "The update moves '{}' itself and no '{}' is registered on this proxy,"
                                + " so there is nowhere to put anybody: nobody is being evacuated"
                                + " and every connected player will be disconnected when the"
                                + " servers stop. The countdown is all the warning they get.",
                        servers.limbo(),
                        servers.limboStandby());
            }
            moving = Set.of();
            return;
        }

        moving = next;
        evacuate(next, room);
    }

    /**
     * The waiting room this run can move people into: {@code limbo}, else a registered standby, else {@code null}.
     *
     * @param next the backends this run is about to stop
     * @param registered whether this proxy has a server under a given name
     */
    static @Nullable String roomFor(
            final Set<String> next, final String limbo, final String standby, final Predicate<String> registered) {
        if (!next.contains(limbo) && registered.test(limbo)) {
            return limbo;
        }
        if (!next.contains(standby) && registered.test(standby)) {
            return standby;
        }
        return null;
    }

    /** The backends of the run under way; empty during a countdown, which can still be cancelled. */
    private Set<String> imminent() {
        return imminent(updates.running());
    }

    /**
     * The services of those holds, as a set to look a backend up in.
     *
     * A DOWN run reaches {@code DONE} in seconds while its hold lasts until somebody presses Start.
     */
    static Set<String> heldServices(final List<eu.nordtal.s2.common.update.ServiceHold> holds) {
        if (holds.isEmpty()) {
            return Set.of();
        }
        final Set<String> services = new HashSet<>();
        for (final eu.nordtal.s2.common.update.ServiceHold hold : holds) {
            services.add(hold.service());
        }
        return Set.copyOf(services);
    }

    /**
     * The backends to clear, empty until a run is under way.
     *
     * @param running the row whose {@code not_before} has passed, from {@code UpdateDirectory#running()}
     */
    static Set<String> imminent(final Optional<UpdateRequest> running) {
        return running.map(Evacuation::backends).orElseGet(Set::of);
    }

    /** The services a request's report says are moving; an unreadable report gives none. */
    static Set<String> backends(final UpdateRequest request) {
        return UpdateReports.parse(request.result())
                .map(report -> report.services().stream()
                        .filter(UpdateReport.ServiceLine::isMoving)
                        .map(UpdateReport.ServiceLine::service)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()))
                .orElseGet(Set::of);
    }

    /** Moves everybody standing on one of those backends into the waiting room. */
    private void evacuate(final Set<String> backends, final String waitingRoom) {
        final RegisteredServer limbo = proxy.getServer(waitingRoom).orElse(null);
        if (limbo == null) {
            // roomFor checked this server, so it was unregistered between the two calls.
            logger.error(
                    "The update moves {} and there is no '{}' registered on this proxy, so"
                            + " nobody can be moved out of the way",
                    backends,
                    waitingRoom);
            return;
        }

        final List<Player> leaving = new java.util.ArrayList<>();
        for (final Player player : proxy.getAllPlayers()) {
            final String on = player.getCurrentServer()
                    .map(connection -> connection.getServerInfo().getName())
                    .orElse(null);
            if (on != null && backends.contains(on)) {
                leaving.add(player);
            }
        }
        if (leaving.isEmpty()) {
            return;
        }

        logger.info(
                "Moving {} player(s) off {} into '{}' before the update stops them",
                leaving.size(),
                new HashSet<>(backends),
                waitingRoom);
        // Recorded before the move, so the release at the other end has a sentence to say.
        homecoming.movedOut(leaving);
        for (final Player player : leaving) {
            // fireAndForget: blocking on one slow connection would delay everybody else past the deadline.
            try {
                player.createConnectionRequest(limbo).fireAndForget();
            } catch (final RuntimeException failure) {
                logger.warn(
                        "Could not move {} into '{}' before the update", player.getUsername(), waitingRoom, failure);
            }
        }
    }
}
