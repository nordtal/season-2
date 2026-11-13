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
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Transfers every player to the standby proxy before an update run stops the live proxy.
 * A player whose move into a waiting room is under way is transferred once that move has ended.
 * It does nothing on the standby and without a {@code public-address}.
 */
public final class ProxySwap {

    /** The compose service name of the proxy. */
    static final String OWN_SERVICE = OnlineCounts.PROXY;

    /** The compose service name of the standby proxy. */
    static final String STANDBY_SERVICE = OWN_SERVICE + "-standby";

    private final ProxyServer proxy;
    private final Logger logger;
    private final UpdateDirectory updates;
    private final SwapStore seats;
    private final ProxyRole role;
    private final @Nullable InetSocketAddress standby;
    private final Clock clock;
    private final StandbyReturn.Probe probe;

    /** Returns the end of the player's move into a waiting room. */
    private final Function<UUID, ? extends CompletionStage<?>> settled;

    /** The start time of this process. */
    private final Instant startedAt;

    /** Whether the network is transferred in this run. */
    private volatile boolean parked;

    /** The id of the run, zero before a run. */
    private volatile long run;

    /**
     * Prepares the swap.
     *
     * @param standby from {@code SwapAddresses#standbyAddress}, {@code null} without a public address
     * @param settled {@code Evacuation#settled}
     */
    public ProxySwap(
            final ProxyServer proxy,
            final Logger logger,
            final UpdateDirectory updates,
            final SwapStore seats,
            final ProxyRole role,
            final @Nullable InetSocketAddress standby,
            final Clock clock,
            final Function<UUID, ? extends CompletionStage<?>> settled) {
        this(proxy, logger, updates, seats, role, standby, clock, settled, StandbyReturn::connects);
    }

    /** @param probe asks whether the standby answers */
    ProxySwap(
            final ProxyServer proxy,
            final Logger logger,
            final UpdateDirectory updates,
            final SwapStore seats,
            final ProxyRole role,
            final @Nullable InetSocketAddress standby,
            final Clock clock,
            final Function<UUID, ? extends CompletionStage<?>> settled,
            final StandbyReturn.Probe probe) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.updates = Objects.requireNonNull(updates, "updates");
        this.seats = Objects.requireNonNull(seats, "seats");
        this.role = Objects.requireNonNull(role, "role");
        this.standby = standby;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.settled = Objects.requireNonNull(settled, "settled");
        this.startedAt = this.clock.instant();
    }

    /** The time that the standby has to answer. */
    static final Duration STANDBY_ANSWERS_WITHIN = Duration.ofSeconds(1);

    /** Returns whether this proxy transfers the network. */
    public boolean isArmed() {
        return standby != null && !role.isStandby();
    }

    /**
     * Returns whether a run that stops this proxy has reached zero.
     * It is false on the standby and without a {@code public-address}.
     */
    public boolean isStopping() {
        return parked;
    }

    /**
     * Returns whether a standby proxy answers.
     * It opens a socket. It is false on the standby and without a {@code public-address}.
     */
    public boolean canPark() {
        final InetSocketAddress target = standby;
        return target != null && isArmed() && probe.answers(target, STANDBY_ANSWERS_WITHIN);
    }

    /**
     * Reads the update request and transfers the players when needed. It runs on every {@code nordtal_update}
     * notification and never throws.
     */
    public synchronized void check() {
        final InetSocketAddress target = standby;
        if (target == null || !isArmed()) {
            return;
        }

        final Optional<eu.nordtal.season.database.update.UpdateRequest> running;
        try {
            running = updates.running();
        } catch (final RuntimeException failure) {
            logger.warn("update row unreadable, nobody parked", failure);
            return;
        }
        final Set<String> next = Evacuation.imminent(running);
        run = running.map(eu.nordtal.season.database.update.UpdateRequest::id).orElse(0L);
        final boolean alreadyMoved = running.map(request -> hasBeenThroughMe(startedAt, request.due()))
                .orElse(false);

        final Pass pass = decide(next, parked, alreadyMoved, () -> probe.answers(target, STANDBY_ANSWERS_WITHIN));
        parked = doorAfter(pass, parked);
        switch (pass) {
            case IDLE, ALREADY_DONE, ALREADY_MOVED -> {}
            case STANDBY_MISSING ->
                logger.warn(
                        "standby proxy not answering, nobody parked: standby_address={}:{}",
                        target.getHostString(),
                        target.getPort());
            case PARK -> park();
        }
    }

    /**
     * Returns whether the proxy accepts no new players after the {@code pass}.
     * It is true for {@code PARK}, {@code STANDBY_MISSING} and {@code ALREADY_DONE}.
     */
    static boolean doorAfter(final Pass pass, final boolean wasShut) {
        return switch (pass) {
            case IDLE, ALREADY_MOVED -> false;
            case ALREADY_DONE -> wasShut;
            case STANDBY_MISSING, PARK -> true;
        };
    }

    /** Returns whether this process started after the zero of the run. */
    static boolean hasBeenThroughMe(final Instant startedAt, final Instant zero) {
        return zero != null && startedAt.isAfter(zero);
    }

    /** The result of {@link #check()}. */
    enum Pass {

        /** No run stops this proxy. */
        IDLE,

        /** A run stops this proxy and this proxy already transferred the players. */
        ALREADY_DONE,

        /** A run stops this proxy and this process was started by the run. */
        ALREADY_MOVED,

        /** A run stops this proxy and no standby answers. */
        STANDBY_MISSING,

        /** A run stops this proxy and the standby answers. */
        PARK
    }

    /** @param standbyAnswers asks whether the standby answers. It is called last because it opens a socket. */
    static Pass decide(
            final Set<String> imminent,
            final boolean alreadyParked,
            final boolean alreadyMoved,
            final BooleanSupplier standbyAnswers) {
        if (!imminent.contains(OWN_SERVICE)) {
            return Pass.IDLE;
        }
        if (alreadyMoved) {
            return Pass.ALREADY_MOVED;
        }
        if (alreadyParked) {
            return Pass.ALREADY_DONE;
        }
        return standbyAnswers.getAsBoolean() ? Pass.PARK : Pass.STANDBY_MISSING;
    }

    /** Stores the seat of every player and transfers the player to the standby. */
    private void park() {
        final var players = proxy.getAllPlayers();
        final InetSocketAddress target = standby;
        if (target == null || players.isEmpty()) {
            logger.info("proxy swap skipped, no player connected");
            return;
        }

        logger.info(
                "players sent to standby proxy: count={} standby_address={}:{}",
                players.size(),
                target.getHostString(),
                target.getPort());
        for (final Player player : players) {
            seat(player);
            settled.apply(player.getUniqueId()).whenComplete((_, _) -> transfer(player));
        }
    }

    /** Stores the count of the transferred players. A failed write is logged. */
    private void moved() {
        if (run == 0) {
            return;
        }
        try {
            updates.recorder().moved(run, OWN_SERVICE, STANDBY_SERVICE, 1);
        } catch (final RuntimeException failure) {
            logger.warn("parked player not recorded", failure);
        }
    }

    /**
     * Stores the seat of the player and transfers the player to the standby.
     * {@code RestartGate} calls it for late logins.
     *
     * @return whether the transfer was sent
     */
    public boolean park(final Player player) {
        seat(player);
        return transfer(player);
    }

    /** Stores the server of the player, which the return to the live proxy reads. */
    private void seat(final Player player) {
        final String on = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null);
        if (on != null) {
            try {
                seats.seat(player.getUniqueId(), on, clock.instant());
            } catch (final RuntimeException failure) {
                logger.warn("player seat not recorded: player={}", player.getUsername(), failure);
            }
        }
    }

    /**
     * Transfers a connected player to the standby.
     *
     * @return whether the transfer was sent
     */
    private boolean transfer(final Player player) {
        if (!player.isActive()) {
            logger.info("player left before the transfer to standby proxy: player={}", player.getUsername());
            return false;
        }
        try {
            player.transferToHost(standby);
            moved();
            return true;
        } catch (final RuntimeException failure) {
            logger.warn("player not transferred to standby proxy: player={}", player.getUsername(), failure);
            return false;
        }
    }
}
