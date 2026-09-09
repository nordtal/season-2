package eu.nordtal.s2.networkcontrol.routing;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.networkcontrol.gate.FallbackCache;
import eu.nordtal.s2.networkcontrol.gate.GateMessages;
import eu.nordtal.s2.networkcontrol.gate.LoginRoster;
import eu.nordtal.s2.networkcontrol.pack.PackStation;
import eu.nordtal.s2.networkcontrol.phase.PhaseWatch;

import net.kyori.adventure.text.Component;

import org.slf4j.Logger;

import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The Velocity half of routing: it turns a {@link RouteDecision} into a connection or a disconnect,
 * and it is the {@link PhaseWatch.ChangeListener} that re-routes everybody when the phase moves.
 *
 * <ul>
 *   <li><b>A phase change moves connected players.</b>
 *       {@link #onPhaseChanged(SeasonPhase, SeasonPhase)} re-reads each player's access state, so a
 *       switch to {@code SMP} disconnects a player without access instead of moving them.</li>
 *   <li><b>Every login lands in {@code limbo} first, whatever the phase.</b>
 *       {@link #onChooseInitialServer} sets the waiting room as the initial server;
 *       {@link eu.nordtal.s2.networkcontrol.pack.PackStation} offers the pack there and hands the
 *       player back to {@link #releaseFromLimbo(Player)}.</li>
 *   <li><b>A player still in the waiting room is not re-routed by a phase change.</b> Their
 *       admission is re-checked, but the connection is left to the pack station, which is the only
 *       thing that knows whether their pack has arrived. Connecting them here would be the one way
 *       onto a backend without the pack.</li>
 * </ul>
 *
 * <p>A server name {@code gate.yml} carries that this proxy does not have produces a disconnect
 * rather than an undefined state. A server that is registered but <em>down</em> cannot be told apart
 * until the connection is attempted; {@link #connect} handles that failure the same way.</p>
 *
 * <p>{@link #onPhaseChanged} is called from {@link PhaseWatch}, which refreshes on three different
 * threads, so it hands the work to the proxy scheduler rather than running one blocking query per
 * connected player on whichever thread noticed.</p>
 */
public final class PlayerRouter implements PhaseWatch.ChangeListener {

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final AccessDirectory access;
    private final PhaseRouting routing;
    private final PhaseWatch phases;
    private final LoginRoster roster;
    private final FallbackCache fallback;
    private final GateMessages messages;
    private final PackStation packs;

    /**
     * Where this class has decided to send somebody, so that {@link RouteIntents} can refuse every
     * destination it did not choose. Every connection below registers one; a path added without one
     * disconnects the player it was written for, loudly, in the proxy log.
     */
    private final RouteIntents intents;

    public PlayerRouter(final Object plugin, final ProxyServer proxy, final Logger logger,
                        final AccessDirectory access, final PhaseRouting routing, final PhaseWatch phases,
                        final LoginRoster roster, final FallbackCache fallback, final GateMessages messages,
                        final PackStation packs, final RouteIntents intents) {
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
    }

    // ------------------------------------------------------------------ login

    /**
     * Sends every admitted login to {@code limbo}, whatever the phase.
     * <p>
     * The phase comes from {@link PhaseWatch#lastKnown()} and the admin flag from
     * {@link LoginRoster}, both already in memory: the login path is one round trip and the gate has
     * already spent it. A player the roster has never heard of was let in by the fallback cache and
     * is treated as a non-admin, which is the safe way round.
     * </p>
     * <p>
     * What happens next is not this method's business. The player arrives in the waiting room, the
     * pack station offers them the pack, and {@link #releaseFromLimbo(Player)} is called when there
     * is nothing left to wait for.
     * </p>
     */
    @Subscribe
    public void onChooseInitialServer(final PlayerChooseInitialServerEvent event) {
        final SeasonPhase phase = phases.lastKnown();
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        final RouteDecision decision =
                routing.decideInitial(phase, roster.isAdmin(uuid), registeredServerNames());

        switch (decision.action()) {
            case CONNECT -> {
                // Before setInitialServer, because Velocity fires ServerPreConnectEvent for the
                // initial connection too - and RouteIntents refuses a destination nothing chose.
                intents.intend(uuid, decision.server());
                proxy.getServer(decision.server()).ifPresent(event::setInitialServer);
                if (!decision.server().equals(routing.servers().limbo())) {
                    // Only an admin on a proxy with no waiting room gets here. Said out loud
                    // because it is the one login that skips the pack.
                    logger.warn("No '{}' server is registered, so admin {} is connected straight to "
                                    + "'{}' in phase {} - WITHOUT the resource pack",
                            routing.servers().limbo(), player.getUsername(), decision.server(),
                            phase);
                }
            }
            // decideInitial never answers STAY, which would leave the choice to velocity.toml's
            // `try` list. Kept as a no-op so a future STAY is a log line rather than a disconnect.
            case STAY -> logger.warn("Routing answered STAY for {} at login in phase {}, which "
                    + "leaves the choice to velocity.toml", player.getUsername(), phase);
            default -> {
                // No waiting room. Clearing the initial server matters as much as the disconnect:
                // without it Velocity would still try velocity.toml's own list, which is exactly
                // the "everybody joined without the resource pack" outcome this refuses.
                event.setInitialServer(null);
                logger.error("No '{}' server is registered on this proxy, so {} cannot be put in the "
                                + "waiting room in phase {} and is being disconnected instead",
                        routing.servers().limbo(), player.getUsername(), phase);
                player.disconnect(reasonFor(decision, roster.localeOf(uuid), null, null));
            }
        }
    }

    /**
     * Connects a player the pack station has finished with to the server their phase points at.
     * <p>
     * The proxy owns routing: {@code limbo}'s message says only that the player is ready, and the
     * destination is worked out here from the phase.
     * </p>
     *
     * @param player a player who is in the waiting room and has nothing left to wait for
     */
    public void releaseFromLimbo(final Player player) {
        final SeasonPhase phase = phases.lastKnown();
        final RouteDecision decision =
                routing.decideRelease(phase, roster.isAdmin(player.getUniqueId()), registeredServerNames());

        switch (decision.action()) {
            // A backend that is registered and down holds the player with the BACKEND title rather
            // than disconnecting them with the "no server" screen.
            case CONNECT -> connect(player, decision.server(), roster.localeOf(player.getUniqueId()),
                    cause -> packs.releaseFailed(player, cause));
            // Unreachable in practice: decideRelease never answers STAY, because a player being
            // released is standing in limbo and staying there is a black screen. A log line rather
            // than an exception - a player sitting in limbo is the better failure.
            case STAY -> logger.warn("The pack station released {} but routing says to leave them "
                    + "where they are, in phase {}", player.getUsername(), phase);
            default -> {
                logger.error("The pack station released {} but routing now says {}",
                        player.getUsername(), decision.action());
                player.disconnect(reasonFor(decision, roster.localeOf(player.getUniqueId()), null, null));
            }
        }
    }

    // ------------------------------------------------------------------ a phase change

    @Override
    public void phaseChanged(final SeasonPhase previous, final SeasonPhase current) {
        onPhaseChanged(previous, current);
    }

    /**
     * Schedules a re-route of every connected player.
     *
     * @param previous what the proxy thought the phase was, {@code null} the first time the row is
     *                 read at all - which is startup, when nobody is connected and there is nothing
     *                 to move
     * @param current  the phase now
     */
    public void onPhaseChanged(final SeasonPhase previous, final SeasonPhase current) {
        if (previous == null) {
            return;
        }
        logger.info("Phase changed {} -> {}: re-routing {} connected players", previous, current,
                proxy.getAllPlayers().size());
        proxy.getScheduler().buildTask(plugin, () -> rerouteAll(current)).schedule();
    }

    /**
     * One pass over every connected player. Public so the {@code /phase} command's own switch and a
     * future admin command can force one; it is otherwise driven by {@link #onPhaseChanged}.
     *
     * @param phase the phase that was just observed, for logging only - each player's own re-read
     *              carries the authoritative phase, and using that keeps admission and destination
     *              on the same row
     * @return how many players were actually moved or disconnected
     */
    public int rerouteAll(final SeasonPhase phase) {
        final Set<String> available = registeredServerNames();
        int acted = 0;
        for (final Player player : proxy.getAllPlayers()) {
            if (rerouteOne(player, available)) {
                acted++;
            }
        }
        logger.info("Re-route for {} finished: {} of {} players moved or disconnected", phase, acted,
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
            logger.warn("Could not re-check {} ({}) while re-routing for a phase change; leaving "
                    + "them where they are", uuid, player.getUsername(), exception);
            return false;
        }
        fallback.remember(uuid, state);
        roster.remember(uuid, state);

        final RouteDecision decision = routing.decide(state, available);
        if (packs.isHeld(uuid) && (decision.action() == RouteDecision.Action.CONNECT
                || decision.action() == RouteDecision.Action.STAY)) {
            // Still in the waiting room: admission stands, but whether they may leave is the pack
            // station's question. Re-asking also updates the title they are looking at. STAY is
            // included because it is what an admin gets on a phase change, and an admin in the room
            // has to be re-asked too or they keep the old title.
            packs.evaluate(player);
            return false;
        }
        return switch (decision.action()) {
            case STAY -> false;
            case CONNECT -> connect(player, decision.server(), state.locale());
            default -> {
                logger.info("Disconnecting {} on the phase change: {}", player.getUsername(),
                        decision.action());
                player.disconnect(reasonFor(decision, state.locale(), state.launch(), Instant.now()));
                yield true;
            }
        };
    }

    /**
     * Moves one player, unless they are already there.
     * <p>
     * {@code connect()} rather than {@code fireAndForget()}: leaving a player sitting on a backend
     * the phase says they should not be on is worse than telling them why they cannot reach the
     * right one.
     * </p>
     */
    private boolean connect(final Player player, final String server, final Locale locale) {
        return connect(player, server, locale, cause -> {
            logger.error("Re-routing {} to '{}' failed: {}", player.getUsername(), server, cause);
            player.disconnect(messages.noServer(locale));
        });
    }

    /**
     * @param onFailure what to do when the connection does not go through, given the reason as one
     *                  line: a phase change disconnects, a release from limbo holds. The caller
     *                  logs, because a restarting backend fails one release per waiting player per
     *                  retry and a stack trace per attempt is a log nobody reads
     */
    private boolean connect(final Player player, final String server, final Locale locale,
                            final java.util.function.Consumer<String> onFailure) {
        final Optional<String> currently = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName());
        if (currently.isPresent() && currently.get().equals(server)) {
            return false;
        }

        final RegisteredServer target = proxy.getServer(server).orElse(null);
        if (target == null) {
            // Only reachable if a server was unregistered between building the name set and here.
            logger.error("'{}' disappeared from this proxy while re-routing {}", server,
                    player.getUsername());
            player.disconnect(messages.noServer(locale));
            return true;
        }

        // RouteIntents refuses every destination nothing chose, so the intent has to be recorded
        // before the request: the event fires inside connect().
        intents.intend(player.getUniqueId(), server);
        player.createConnectionRequest(target).connect().whenComplete((result, error) -> {
            if (error != null) {
                onFailure.accept(error.toString());
                return;
            }
            if (result.getStatus() != ConnectionRequestBuilder.Status.SUCCESS
                    && result.getStatus() != ConnectionRequestBuilder.Status.ALREADY_CONNECTED) {
                onFailure.accept(result.getStatus() + (result.getReasonComponent()
                        .map(reason -> ": " + net.kyori.adventure.text.serializer.plain
                                .PlainTextComponentSerializer.plainText().serialize(reason))
                        .orElse("")));
            }
        });
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private Set<String> registeredServerNames() {
        final Set<String> names = new HashSet<>();
        proxy.getAllServers().forEach(server -> names.add(server.getServerInfo().getName()));
        return names;
    }

    /**
     * @param launch the announced opening instant, and {@code now} the instant to count from -
     *               both {@code null} on the two paths that cannot produce a {@code PRE_LAUNCH}
     *               refusal, where the screen is then shown without its countdown line rather than
     *               refusing to render
     */
    private Component reasonFor(final RouteDecision decision, final Locale locale,
                                final Instant launch, final Instant now) {
        return switch (decision.action()) {
            case REFUSE_UNLINKED -> messages.unlinked(locale);
            case REFUSE_NOT_MEMBER -> messages.notMember(locale);
            // The same message the login gate uses.
            case REFUSE_NO_ACCESS -> messages.noAccess(locale);
            case REFUSE_MAINTENANCE_UNAVAILABLE -> messages.maintenance(locale);
            case REFUSE_NO_SERVER -> messages.noServer(locale);
            case REFUSE_PRE_LAUNCH_BUY -> messages.preLaunchBuy(locale, launch, now);
            case REFUSE_PRE_LAUNCH_READY -> messages.preLaunchReady(locale, launch, now);
            case CONNECT, STAY -> throw new IllegalArgumentException(
                    "not a refusal: " + decision.action());
        };
    }
}
