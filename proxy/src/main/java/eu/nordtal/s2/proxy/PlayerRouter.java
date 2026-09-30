package eu.nordtal.s2.proxy;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.database.access.AccessReader;
import eu.nordtal.s2.database.access.AccessState;
import eu.nordtal.s2.proxy.gate.BackendHealth;
import eu.nordtal.s2.proxy.gate.FallbackCache;
import eu.nordtal.s2.proxy.gate.GateMessages;
import eu.nordtal.s2.proxy.gate.LoginRoster;
import eu.nordtal.s2.proxy.pack.PackStation;
import eu.nordtal.s2.proxy.phase.PhaseWatch;
import eu.nordtal.s2.proxy.routing.PhaseRouting;
import eu.nordtal.s2.proxy.routing.RouteDecision;
import eu.nordtal.s2.proxy.routing.RouteIntents;
import eu.nordtal.s2.proxy.update.ParkedSeats;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The Velocity half of routing: turns a {@link RouteDecision} into a connection or a disconnect.
 *
 * A phase change re-checks everyone but leaves players in the waiting room to the pack station.
 */
public final class PlayerRouter implements PhaseWatch.ChangeListener {

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final AccessReader access;
    private final PhaseRouting routing;
    private final PhaseWatch phases;
    private final LoginRoster roster;
    private final FallbackCache fallback;
    private final GateMessages messages;
    private final PackStation packs;
    private final BackendHealth health;

    /** Every destination chosen here, so {@link RouteIntents} can refuse the ones nothing chose. */
    private final RouteIntents intents;

    /** Where each player stood before the last proxy swap; empty on a start that did not follow one. */
    private final ParkedSeats seats;

    /** Tells a player a run moved out of the way before they return. */
    private final eu.nordtal.s2.proxy.update.Homecoming homecoming;

    private final Clock clock;

    public PlayerRouter(
            final Object plugin,
            final ProxyServer proxy,
            final Logger logger,
            final AccessReader access,
            final PhaseRouting routing,
            final PhaseWatch phases,
            final LoginRoster roster,
            final FallbackCache fallback,
            final GateMessages messages,
            final PackStation packs,
            final RouteIntents intents,
            final BackendHealth health,
            final ParkedSeats seats,
            final eu.nordtal.s2.proxy.update.Homecoming homecoming,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.access = Objects.requireNonNull(access, "access");
        this.routing = Objects.requireNonNull(routing, "routing");
        this.phases = Objects.requireNonNull(phases, "phases");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.packs = Objects.requireNonNull(packs, "packs");
        this.intents = Objects.requireNonNull(intents, "intents");
        this.health = Objects.requireNonNull(health, "health");
        this.seats = Objects.requireNonNull(seats, "seats");
        this.homecoming = Objects.requireNonNull(homecoming, "homecoming");
    }

    /**
     * Sends every admitted login to {@code limbo}, whatever the phase.
     *
     * Reads only memory, the phase from {@link PhaseWatch#lastKnown()} and the admin flag from {@link LoginRoster}.
     */
    @Subscribe
    public void onChooseInitialServer(final PlayerChooseInitialServerEvent event) {
        final SeasonPhase phase = phases.lastKnown();
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        final RouteDecision decision = routing.decideInitial(phase, roster.isAdmin(uuid), registeredServerNames());

        switch (decision.action()) {
            case CONNECT -> {
                final String server = Objects.requireNonNull(decision.server(), "CONNECT always carries a server");
                // Before setInitialServer: Velocity fires ServerPreConnectEvent for the initial connection too.
                intents.intend(uuid, server);
                proxy.getServer(server).ifPresent(event::setInitialServer);
                if (!routing.servers().isWaitingRoom(server)) {
                    // Only an admin on a proxy with no waiting room gets here, skipping the pack.
                    logger.warn(
                            "No '{}' server is registered, so admin {} is connected straight to "
                                    + "'{}' in phase {} - WITHOUT the resource pack",
                            routing.servers().limbo(),
                            player.getUsername(),
                            server,
                            phase);
                }
            }
            // decideInitial never answers STAY; a future STAY logs rather than disconnects.
            case STAY ->
                logger.warn(
                        "Routing answered STAY for {} at login in phase {}, which "
                                + "leaves the choice to velocity.toml",
                        player.getUsername(),
                        phase);
            default -> {
                // No waiting room. Clearing the initial server too, or Velocity falls back to velocity.toml's list.
                event.setInitialServer(null);
                logger.error(
                        "No '{}' server is registered on this proxy, so {} cannot be put in the "
                                + "waiting room in phase {} and is being disconnected instead",
                        routing.servers().limbo(),
                        player.getUsername(),
                        phase);
                player.disconnect(reasonFor(decision, roster.localeOf(uuid), null, null));
            }
        }
    }

    /**
     * Connects a player the pack station has finished with to the server their phase points at.
     *
     * @param player a player in the waiting room with nothing left to wait for
     */
    public void releaseFromLimbo(final Player player) {
        final SeasonPhase phase = phases.lastKnown();
        final RouteDecision decision =
                routing.decideRelease(phase, roster.isAdmin(player.getUniqueId()), registeredServerNames());

        switch (decision.action()) {
            // A registered but down backend holds the player with the BACKEND title instead of disconnecting them.
            case CONNECT -> {
                final String phaseDestination =
                        Objects.requireNonNull(decision.server(), "CONNECT always carries a server");
                // Only here: for everybody but an admin the seat names the server the phase names anyway.
                final String destination = seats.releaseTo(
                        player.getUniqueId(),
                        roster.isAdmin(player.getUniqueId()),
                        phaseDestination,
                        registeredServerNames(),
                        routing.servers());
                // Once per parked player, since this is also every ordinary login's last step.
                homecoming.comingBack(player, destination);
                connect(
                        player,
                        destination,
                        roster.localeOf(player.getUniqueId()),
                        cause -> packs.releaseFailed(player, cause));
            }
            // Unreachable: decideRelease never answers STAY, since staying in limbo is a black screen.
            case STAY ->
                logger.warn(
                        "The pack station released {} but routing says to leave them " + "where they are, in phase {}",
                        player.getUsername(),
                        phase);
            default -> {
                logger.error(
                        "The pack station released {} but routing now says {}",
                        player.getUsername(),
                        decision.action());
                player.disconnect(reasonFor(decision, roster.localeOf(player.getUniqueId()), null, null));
            }
        }
    }

    @Override
    public void phaseChanged(final @Nullable SeasonPhase previous, final SeasonPhase current) {
        onPhaseChanged(previous, current);
    }

    /**
     * Schedules a re-route of every connected player on the proxy scheduler.
     *
     * @param previous the phase before, {@code null} on the first read at startup
     * @param current the phase now
     */
    public void onPhaseChanged(final @Nullable SeasonPhase previous, final SeasonPhase current) {
        if (previous == null) {
            return;
        }
        logger.info(
                "Phase changed {} -> {}: re-routing {} connected players",
                previous,
                current,
                proxy.getAllPlayers().size());
        proxy.getScheduler().buildTask(plugin, () -> rerouteAll(current)).schedule();
    }

    /**
     * Re-routes every connected player once.
     *
     * @param phase for logging only; each player's re-read carries the phase used
     * @return how many players were moved or disconnected
     */
    public int rerouteAll(final SeasonPhase phase) {
        final Set<String> available = registeredServerNames();
        int acted = 0;
        for (final Player player : proxy.getAllPlayers()) {
            if (rerouteOne(player, available)) {
                acted++;
            }
        }
        logger.info(
                "Re-route for {} finished: {} of {} players moved or disconnected",
                phase,
                acted,
                proxy.getAllPlayers().size());
        return acted;
    }

    private boolean rerouteOne(final Player player, final Set<String> available) {
        final UUID uuid = player.getUniqueId();

        final AccessState state;
        try {
            state = access.accessState(uuid);
        } catch (final RuntimeException exception) {
            // Same rule as the expiry sweep: a database hiccup must not read as a mass eviction.
            logger.warn(
                    "Could not re-check {} ({}) while re-routing for a phase change; leaving " + "them where they are",
                    uuid,
                    player.getUsername(),
                    exception);
            return false;
        }
        fallback.remember(uuid, state);
        roster.remember(uuid, state);

        final RouteDecision decision = routing.decide(state, available);
        if (packs.isHeld(uuid)
                && (decision.action() == RouteDecision.Action.CONNECT
                        || decision.action() == RouteDecision.Action.STAY)) {
            // Still waiting: the pack station decides, and re-asking updates the title.
            packs.evaluate(player);
            return false;
        }
        return switch (decision.action()) {
            case STAY -> false;
            case CONNECT ->
                connect(
                        player,
                        Objects.requireNonNull(decision.server(), "CONNECT always carries a server"),
                        state.locale());
            default -> {
                logger.info("Disconnecting {} on the phase change: {}", player.getUsername(), decision.action());
                player.disconnect(reasonFor(decision, state.locale(), state.launch(), clock.instant()));
                yield true;
            }
        };
    }

    /** Moves one player, unless they are already there. */
    private boolean connect(final Player player, final String server, final Locale locale) {
        return connect(player, server, locale, cause -> {
            logger.error("Re-routing {} to '{}' failed: {}", player.getUsername(), server, cause);
            player.disconnect(messages.noServer(locale));
        });
    }

    /**
     * Connects a player, logging failures without a stack trace.
     *
     * @param onFailure given the reason as one line: a phase change disconnects, a release from limbo holds
     */
    private boolean connect(
            final Player player,
            final String server,
            final Locale locale,
            final java.util.function.Consumer<String> onFailure) {
        final Optional<String> currently = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName());
        if (currently.isPresent() && currently.get().equals(server)) {
            return false;
        }

        final RegisteredServer target = proxy.getServer(server).orElse(null);
        if (target == null) {
            // Only reachable if a server was unregistered between building the name set and here.
            logger.error("'{}' disappeared from this proxy while re-routing {}", server, player.getUsername());
            player.disconnect(messages.noServer(locale));
            return true;
        }

        // RouteIntents refuses every destination nothing chose, so the intent is recorded first.
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
            // A successful connection is BackendHealth's only health check.
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
     * Renders the refusal screen for a decision.
     *
     * @param launch the announced opening, {@code null} where no {@code PRE_LAUNCH} refusal can occur
     */
    private Component reasonFor(
            final RouteDecision decision,
            final Locale locale,
            final @Nullable Instant launch,
            final @Nullable Instant now) {
        return switch (decision.action()) {
            case REFUSE_UNLINKED -> messages.unlinked(locale);
            case REFUSE_NOT_MEMBER -> messages.notMember(locale);
            case REFUSE_NO_ACCESS -> messages.noAccess(locale);
            case REFUSE_MAINTENANCE_UNAVAILABLE -> messages.maintenance(locale);
            case REFUSE_NO_SERVER -> messages.noServer(locale);
            case REFUSE_PRE_LAUNCH_BUY -> messages.preLaunchBuy(locale, launch, now);
            case REFUSE_PRE_LAUNCH_READY -> messages.preLaunchReady(locale, launch, now);
            case CONNECT, STAY -> throw new IllegalArgumentException("not a refusal: " + decision.action());
        };
    }
}
