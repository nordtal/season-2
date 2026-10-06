package eu.nordtal.season.proxy.update;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.proxy.ProxyRole;
import eu.nordtal.season.proxy.online.OnlineCounts;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The live proxy's half of a proxy swap: it parks the whole network on the standby before it stops itself.
 *
 * Without a {@code public-address}, or on the standby itself, it does nothing and an update drops the network.
 */
public final class ProxySwap {

    /**
     * The proxy's compose service name, as steward's report spells it; {@code ProxySwapDecisionTest} pins it.
     */
    static final String OWN_SERVICE = OnlineCounts.PROXY;

    private final ProxyServer proxy;
    private final Logger logger;
    private final UpdateDirectory updates;
    private final SwapStore seats;
    private final ProxyRole role;
    private final @Nullable InetSocketAddress standby;
    private final Clock clock;
    private final StandbyReturn.Probe probe;

    /** When this process started, so a run that has already restarted it is not mistaken for one about to. */
    private final Instant startedAt;

    /** Whether this run has parked the network; read from outside, whether the door stays shut until the stop. */
    private volatile boolean parked;

    /**
     * Arms the swap towards the standby.
     *
     * @param standby from {@code SwapAddresses#standbyAddress}, or {@code null} when no public address is configured
     */
    public ProxySwap(
            final ProxyServer proxy,
            final Logger logger,
            final UpdateDirectory updates,
            final SwapStore seats,
            final ProxyRole role,
            final @Nullable InetSocketAddress standby,
            final Clock clock) {
        this(proxy, logger, updates, seats, role, standby, clock, StandbyReturn::connects);
    }

    /**
     * Takes the standby probe as a seam, so the decision is testable without binding a port.
     *
     * @param probe how to ask whether the standby is actually up
     */
    ProxySwap(
            final ProxyServer proxy,
            final Logger logger,
            final UpdateDirectory updates,
            final SwapStore seats,
            final ProxyRole role,
            final @Nullable InetSocketAddress standby,
            final Clock clock,
            final StandbyReturn.Probe probe) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.updates = Objects.requireNonNull(updates, "updates");
        this.seats = Objects.requireNonNull(seats, "seats");
        this.role = Objects.requireNonNull(role, "role");
        this.standby = standby;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.startedAt = this.clock.instant();
    }

    /** How long the standby gets to answer before this pass decides it is not there. */
    static final Duration STANDBY_ANSWERS_WITHIN = Duration.ofSeconds(1);

    /** Returns whether this proxy will park the network rather than drop it, for the startup log line. */
    public boolean isArmed() {
        return standby != null && !role.isStandby();
    }

    /**
     * Returns whether a run stopping this proxy has reached zero, so nobody new is let in.
     *
     * False on the standby and on a proxy with no {@code public-address}.
     */
    public boolean isStopping() {
        return parked;
    }

    /**
     * Returns whether a standby proxy is answering right now; it opens a socket, so ask once per countdown.
     *
     * False on the standby itself and on a proxy with no {@code public-address}.
     */
    public boolean canPark() {
        final InetSocketAddress target = standby;
        return target != null && isArmed() && probe.answers(target, STANDBY_ANSWERS_WITHIN);
    }

    /** One pass, on every {@code nordtal_update} notification and at zero; never throws. */
    public synchronized void check() {
        final InetSocketAddress target = standby;
        if (target == null || !isArmed()) {
            return;
        }

        final Optional<eu.nordtal.season.database.update.UpdateRequest> running;
        try {
            running = updates.running();
        } catch (final RuntimeException failure) {
            logger.warn("Could not read the update row; nobody was parked this pass", failure);
            return;
        }
        final Set<String> next = Evacuation.imminent(running);
        final boolean alreadyMoved = running.map(request -> hasBeenThroughMe(startedAt, request.due()))
                .orElse(false);

        final Pass pass = decide(next, parked, alreadyMoved, () -> probe.answers(target, STANDBY_ANSWERS_WITHIN));
        // The door is decided separately: park() happens once, being shut lasts as long as the run.
        parked = doorAfter(pass, parked);
        switch (pass) {
            case IDLE, ALREADY_DONE, ALREADY_MOVED -> {}
            case STANDBY_MISSING ->
                logger.warn(
                        "The proxy is about to stop and {}:{} does not answer, so nobody can "
                                + "be parked - this update takes the network down the way it "
                                + "always did. The standby has to be running BEFORE the run "
                                + "reaches this proxy.",
                        target.getHostString(),
                        target.getPort());
            case PARK -> park();
        }
    }

    /**
     * Whether the door is shut once a pass has returned {@code pass}.
     *
     * Shut by {@code PARK} or {@code STANDBY_MISSING} and through {@code ALREADY_DONE}; open on {@code IDLE}.
     */
    static boolean doorAfter(final Pass pass, final boolean wasShut) {
        return switch (pass) {
            // ALREADY_MOVED is the run finished with this proxy while still running; the door has to stay open.
            case IDLE, ALREADY_MOVED -> false;
            case ALREADY_DONE -> wasShut;
            case STANDBY_MISSING, PARK -> true;
        };
    }

    /**
     * Returns whether this process started after the run's zero, which only the run itself can have caused.
     *
     * @param startedAt when this process started
     * @param zero the run's own zero, from its row
     */
    static boolean hasBeenThroughMe(final Instant startedAt, final Instant zero) {
        return zero != null && startedAt.isAfter(zero);
    }

    /** What one pass of {@link #check()} does. */
    enum Pass {

        /** No run is about to stop this proxy. */
        IDLE,

        /** One is, and this proxy has already acted on it. */
        ALREADY_DONE,

        /** One is, and this process was started by it: the opposite of {@link #IDLE} that wants the same inaction. */
        ALREADY_MOVED,

        /** One is, and there is no standby answering to park the network on. */
        STANDBY_MISSING,

        /** One is, the standby is up, and everybody goes there now. */
        PARK
    }

    /**
     * The whole decision, without a proxy, a socket or a clock; a standby that does not answer is never parked on.
     *
     * @param standbyAnswers asked last, since it opens a socket
     */
    static Pass decide(
            final Set<String> imminent,
            final boolean alreadyParked,
            final boolean alreadyMoved,
            final BooleanSupplier standbyAnswers) {
        if (!imminent.contains(OWN_SERVICE)) {
            // IDLE rather than a timer, so a second proxy run in the same session parks again.
            return Pass.IDLE;
        }
        if (alreadyMoved) {
            // This process is what the run already started; parking now would hand the network over twice.
            return Pass.ALREADY_MOVED;
        }
        if (alreadyParked) {
            return Pass.ALREADY_DONE;
        }
        return standbyAnswers.getAsBoolean() ? Pass.PARK : Pass.STANDBY_MISSING;
    }

    /** Seats everybody and hands them the standby's address; a failed seat write does not stop a transfer. */
    private void park() {
        final var players = proxy.getAllPlayers();
        final InetSocketAddress target = standby;
        if (target == null || players.isEmpty()) {
            logger.info("The update moves this proxy and nobody is connected: nothing to park");
            return;
        }

        logger.info(
                "The update moves this proxy: parking {} player(s) on {}:{} until it is back",
                players.size(),
                target.getHostString(),
                target.getPort());
        for (final Player player : players) {
            park(player);
        }
    }

    /**
     * Seats one player and hands them the standby's address; {@code RestartGate} uses it for late arrivals.
     *
     * @param player who to hand over
     * @return whether the transfer was sent
     */
    public boolean park(final Player player) {
        final String on = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null);
        if (on != null) {
            try {
                seats.seat(player.getUniqueId(), on, clock.instant());
            } catch (final RuntimeException failure) {
                logger.warn(
                        "Could not record where {} was standing; they will be routed by the"
                                + " phase when they come back",
                        player.getUsername(),
                        failure);
            }
        }
        try {
            player.transferToHost(standby);
            return true;
        } catch (final RuntimeException failure) {
            // Velocity refuses the transfer outright for a client older than 1.20.5; caught per player for that reason.
            logger.warn("Could not transfer {} to the standby proxy", player.getUsername(), failure);
            return false;
        }
    }
}
