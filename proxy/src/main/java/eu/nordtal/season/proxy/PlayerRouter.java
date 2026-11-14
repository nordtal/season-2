package eu.nordtal.season.proxy;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.access.AccessReader;
import eu.nordtal.season.database.access.AccessState;
import eu.nordtal.season.proxy.gate.BackendHealth;
import eu.nordtal.season.proxy.gate.FallbackCache;
import eu.nordtal.season.proxy.gate.GateMessages;
import eu.nordtal.season.proxy.gate.GateOutcome;
import eu.nordtal.season.proxy.gate.LoginRoster;
import eu.nordtal.season.proxy.pack.PackStation;
import eu.nordtal.season.proxy.routing.RouteDecision;
import eu.nordtal.season.proxy.routing.RouteIntents;
import eu.nordtal.season.proxy.routing.SeasonRouting;
import eu.nordtal.season.proxy.season.SeasonWatch;
import eu.nordtal.season.proxy.update.ParkedSeats;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Executes a {@link RouteDecision} as a connection or a disconnect.
 * A season change leaves the players in the waiting room to the pack station.
 */
public final class PlayerRouter implements SeasonWatch.ChangeListener {

    private final Scheduler scheduler;
    private final ProxyServer proxy;
    private final Logger logger;
    private final AccessReader access;
    private final SeasonRouting routing;
    private final SeasonWatch seasons;
    private final LoginRoster roster;
    private final FallbackCache fallback;
    private final GateMessages messages;
    private final PackStation packs;
    private final BackendHealth health;

    /** The intents of the chosen servers. */
    private final RouteIntents intents;

    /** The servers of the players before the last proxy swap. */
    private final ParkedSeats seats;

    /** Informs a returning player about the update run. */
    private final eu.nordtal.season.proxy.update.Homecoming homecoming;

    private final Clock clock;

    /** Tries the waiting rooms for a player whose re-route failed, before the disconnect. */
    private final WaitingRoomRetry retry;

    /** Whether an update run stops the server. */
    private volatile Predicate<String> stopping = server -> false;

    public PlayerRouter(
            final Scheduler scheduler,
            final ProxyServer proxy,
            final Logger logger,
            final AccessReader access,
            final SeasonRouting routing,
            final SeasonWatch seasons,
            final LoginRoster roster,
            final FallbackCache fallback,
            final GateMessages messages,
            final PackStation packs,
            final RouteIntents intents,
            final BackendHealth health,
            final ParkedSeats seats,
            final eu.nordtal.season.proxy.update.Homecoming homecoming,
            final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.access = Objects.requireNonNull(access, "access");
        this.routing = Objects.requireNonNull(routing, "routing");
        this.seasons = Objects.requireNonNull(seasons, "seasons");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.packs = Objects.requireNonNull(packs, "packs");
        this.intents = Objects.requireNonNull(intents, "intents");
        this.health = Objects.requireNonNull(health, "health");
        this.seats = Objects.requireNonNull(seats, "seats");
        this.homecoming = Objects.requireNonNull(homecoming, "homecoming");
        this.retry = new WaitingRoomRetry(
                scheduler,
                routing.servers(),
                name -> proxy.getServer(name).isPresent() && !stopping.test(name),
                logger);
    }

    /** Sets the check for a server that an update run stops, which no login is sent to. */
    public void whenStopping(final Predicate<String> stopping) {
        this.stopping = Objects.requireNonNull(stopping, "stopping");
    }

    /**
     * Sets {@code limbo} as the initial server of every admitted login.
     * It reads {@link SeasonWatch#state()} and {@link LoginRoster}.
     */
    @Subscribe
    public void onChooseInitialServer(final PlayerChooseInitialServerEvent event) {
        final SeasonState season = seasons.state();
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        final RouteDecision decision =
                routing.decideInitial(season, roster.isAdmin(uuid), registeredServerNames(), stopping);

        switch (decision.action()) {
            case CONNECT -> {
                final String server = Objects.requireNonNull(decision.server(), "CONNECT always carries a server");
                intents.intend(uuid, server);
                proxy.getServer(server).ifPresent(event::setInitialServer);
                if (!routing.servers().isWaitingRoom(server)) {
                    logger.warn(
                            "waiting room missing, admin connected without resource pack: waiting_room={} player={} server={} season=\"{}\"",
                            routing.servers().limbo(),
                            player.getUsername(),
                            server,
                            season);
                }
            }
            case STAY ->
                logger.warn("login route left to velocity: player={} season=\"{}\"", player.getUsername(), season);
            default -> {
                event.setInitialServer(null);
                logger.error(
                        "waiting room missing, player disconnected: waiting_room={} player={} season=\"{}\"",
                        routing.servers().limbo(),
                        player.getUsername(),
                        season);
                player.disconnect(reasonFor(decision, roster.localeOf(uuid), null, clock.instant()));
            }
        }
    }

    /**
     * Connects a player whom the pack station released to the server of the active mode, through the gate.
     * A refused player gets the screen of the refusal, and a player whom maintenance holds waits again.
     */
    public void releaseFromLimbo(final Player player) {
        final UUID uuid = player.getUniqueId();
        final Instant now = clock.instant();
        final Set<String> available = registeredServerNames();
        AccessState state = null;
        RouteDecision decision;
        try {
            state = access.accessState(uuid);
            fallback.remember(uuid, state);
            roster.remember(uuid, state);
            decision = routing.decideRelease(state, available, now);
        } catch (final RuntimeException exception) {
            logger.warn(
                    "access not re-checked on release, last season used: uuid={} player={}",
                    uuid,
                    player.getUsername(),
                    exception);
            decision = routing.decideRemembered(seasons.state(), roster.isAdmin(uuid), available);
        }
        final Locale locale = state == null ? roster.localeOf(uuid) : state.locale();

        switch (decision.action()) {
            case CONNECT -> {
                final String modeDestination =
                        Objects.requireNonNull(decision.server(), "CONNECT always carries a server");
                final String destination =
                        seats.releaseTo(uuid, roster.isAdmin(uuid), modeDestination, available, routing.servers());
                homecoming.comingBack(player, destination);
                connect(player, destination, cause -> packs.releaseFailed(player, cause));
            }
            case STAY -> {
                logger.info("player held again for maintenance: player={}", player.getUsername());
                packs.holdAgain(player);
                scheduler.execute(() -> {
                    final boolean _ = seasons.refresh();
                });
            }
            default -> {
                logger.info("player refused on release: player={} action={}", player.getUsername(), decision.action());
                player.disconnect(reasonFor(decision, locale, state, now));
            }
        }
    }

    @Override
    public void seasonChanged(final @Nullable SeasonState previous, final SeasonState current) {
        onSeasonChanged(previous, current);
    }

    /** Schedules a re-route of every connected player. The first read changes nothing. */
    public void onSeasonChanged(final @Nullable SeasonState previous, final SeasonState current) {
        if (previous == null) {
            return;
        }
        logger.info(
                "season changed, re-route scheduled: from=\"{}\" to=\"{}\" count={}",
                previous,
                current,
                proxy.getAllPlayers().size());
        scheduler.execute(() -> rerouteAll(current));
    }

    /**
     * Re-routes every connected player by the gate.
     *
     * @param season the state for the log
     * @return the number of moved or disconnected players
     */
    public int rerouteAll(final SeasonState season) {
        final Set<String> available = registeredServerNames();
        final Instant now = clock.instant();
        int acted = 0;
        for (final Player player : proxy.getAllPlayers()) {
            if (rerouteOne(player, available, now)) {
                acted++;
            }
        }
        logger.info(
                "re-route finished: season=\"{}\" acted={} count={}",
                season,
                acted,
                proxy.getAllPlayers().size());
        return acted;
    }

    private boolean rerouteOne(final Player player, final Set<String> available, final Instant now) {
        final UUID uuid = player.getUniqueId();

        final AccessState state;
        try {
            state = access.accessState(uuid);
        } catch (final RuntimeException exception) {
            logger.warn(
                    "access not re-checked on season change, player left in place: uuid={} player={}",
                    uuid,
                    player.getUsername(),
                    exception);
            return false;
        }
        fallback.remember(uuid, state);
        roster.remember(uuid, state);

        final RouteDecision decision = routing.decide(state, available, now);
        final boolean moves =
                decision.action() == RouteDecision.Action.CONNECT || decision.action() == RouteDecision.Action.STAY;
        if (moves && packs.isHeld(uuid)) {
            packs.evaluate(player);
            return false;
        }
        if (moves && packs.isSwitching()) {
            // The evacuation of the switch moves the players, and the waiting room releases them.
            return false;
        }
        return switch (decision.action()) {
            case STAY -> false;
            case CONNECT ->
                connect(
                        player,
                        viaWaitingRoom(
                                Objects.requireNonNull(decision.server(), "CONNECT always carries a server"),
                                player.getCurrentServer()
                                        .map(on -> on.getServerInfo().getName())
                                        .orElse(null),
                                routing.servers(),
                                available),
                        state.locale());
            default -> {
                logger.info(
                        "player disconnected on season change: player={} action={}",
                        player.getUsername(),
                        decision.action());
                player.disconnect(reasonFor(decision, state.locale(), state, now));
                yield true;
            }
        };
    }

    /**
     * Returns the waiting room for a move onto {@code smp}, whose queue lets the player on; otherwise the server.
     * Without a registered {@code limbo} the player goes straight there, and a player on the server stays.
     */
    static String viaWaitingRoom(
            final String server,
            final @Nullable String current,
            final NetworkServers servers,
            final Set<String> available) {
        if (server.equals(current)) {
            return server;
        }
        final String limbo = servers.limbo();
        return server.equals(servers.smp()) && available.contains(limbo) ? limbo : server;
    }

    /**
     * Connects the player unless the player is on the server.
     * A failed move tries the waiting rooms through {@link WaitingRoomRetry} before it disconnects the player.
     */
    private boolean connect(final Player player, final String server, final Locale locale) {
        return connect(player, server, cause -> {
            logger.warn(
                    "re-route failed, waiting room tried next: player={} server={} cause={}",
                    player.getUsername(),
                    server,
                    cause);
            retry.start(player.getUsername(), server, room -> intoRoom(player, room), () -> {
                if (player.isActive()) {
                    logger.error(
                            "re-route failed, player disconnected: player={} server={}", player.getUsername(), server);
                    player.disconnect(messages.noServer(locale));
                }
            });
        });
    }

    /** Moves the player into the waiting room; completes with {@code true} once the player is there or gone. */
    private CompletableFuture<Boolean> intoRoom(final Player player, final String room) {
        if (!player.isActive()) {
            return CompletableFuture.completedFuture(true);
        }
        final RegisteredServer target = proxy.getServer(room).orElse(null);
        if (target == null) {
            return CompletableFuture.completedFuture(false);
        }
        intents.intend(player.getUniqueId(), room);
        return player.createConnectionRequest(target).connect().thenApply(result -> {
            final boolean there = result.getStatus() == ConnectionRequestBuilder.Status.SUCCESS
                    || result.getStatus() == ConnectionRequestBuilder.Status.ALREADY_CONNECTED;
            if (there) {
                health.clear(room);
                logger.info(
                        "player moved into waiting room after a failed re-route: player={} waiting_room={}",
                        player.getUsername(),
                        room);
            }
            return there;
        });
    }

    /**
     * Connects the player and logs a failure without a stack trace.
     *
     * @param onFailure receives the reason as one line
     */
    private boolean connect(
            final Player player, final String server, final java.util.function.Consumer<String> onFailure) {
        final Optional<String> currently = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName());
        if (currently.isPresent() && currently.get().equals(server)) {
            return false;
        }

        final RegisteredServer target = proxy.getServer(server).orElse(null);
        if (target == null) {
            logger.error("server gone during re-route: server={} player={}", server, player.getUsername());
            onFailure.accept("server not registered");
            return true;
        }

        intents.intend(player.getUniqueId(), server);
        final var _ = player.createConnectionRequest(target).connect().whenComplete((result, error) -> {
            if (error != null) {
                onFailure.accept(error.toString());
                return;
            }
            if (result.getStatus() != ConnectionRequestBuilder.Status.SUCCESS
                    && result.getStatus() != ConnectionRequestBuilder.Status.ALREADY_CONNECTED) {
                onFailure.accept(result.getStatus()
                        + result.getReasonComponent()
                                .map(reason -> ": "
                                        + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                                                .plainText()
                                                .serialize(reason))
                                .orElse(""));
                return;
            }
            health.clear(server);
        });
        return true;
    }

    private Set<String> registeredServerNames() {
        final Set<String> names = new HashSet<>();
        proxy.getAllServers().forEach(server -> names.add(server.getServerInfo().getName()));
        return names;
    }

    /**
     * Returns the screen for the refusal.
     *
     * @param state the access of the player, {@code null} when it was not read
     */
    private Component reasonFor(
            final RouteDecision decision, final Locale locale, final @Nullable AccessState state, final Instant now) {
        return switch (decision.action()) {
            case REFUSE_UNLINKED -> messages.unlinked(locale);
            case REFUSE_NOT_MEMBER -> messages.notMember(locale);
            case REFUSE_NO_ACCESS -> messages.noAccess(locale);
            case REFUSE_MAINTENANCE_UNAVAILABLE -> messages.maintenance(locale);
            case REFUSE_NO_SERVER -> messages.noServer(locale);
            case REFUSE_CLOSED -> beforeLaunch(GateOutcome.CLOSED, locale, state, now);
            case REFUSE_COUNTDOWN_BUY -> beforeLaunch(GateOutcome.COUNTDOWN_BUY, locale, state, now);
            case REFUSE_COUNTDOWN_READY -> beforeLaunch(GateOutcome.COUNTDOWN_READY, locale, state, now);
            case CONNECT, STAY -> throw new IllegalArgumentException("not a refusal: " + decision.action());
        };
    }

    /** Returns the screen of a season that has not launched, the trouble screen without a read state. */
    private Component beforeLaunch(
            final GateOutcome outcome, final Locale locale, final @Nullable AccessState state, final Instant now) {
        return state == null ? messages.trouble(locale) : messages.beforeLaunch(outcome, state, now);
    }
}
