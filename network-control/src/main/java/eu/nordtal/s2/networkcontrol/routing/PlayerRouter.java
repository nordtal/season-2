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
import eu.nordtal.s2.networkcontrol.phase.PhaseWatch;

import net.kyori.adventure.text.Component;

import org.slf4j.Logger;

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
 * <h2>What this class does, and what it deliberately does not</h2>
 * <ul>
 *   <li><b>A phase change moves connected players.</b> docs/season-phases.md#routing: "a phase
 *       switch while players are online moves everyone: the proxy re-routes connected players to
 *       the new phase's server". {@link #onPhaseChanged(SeasonPhase, SeasonPhase)} is that, and it
 *       re-reads each player's access state so a switch to {@code SMP} disconnects a player without
 *       access instead of moving them - which is the exception the same section settles.</li>
 *   <li><b>A login during {@code MAINTENANCE} lands in {@code limbo}.</b> Decided 2026-08-31: the
 *       gate admits a linked member during maintenance and the explanation is shown in the waiting
 *       room rather than on a disconnect screen. {@link #onChooseInitialServer} is that.</li>
 *   <li><b>A login in any other phase is left alone.</b> This is the honest gap, not an oversight.
 *       docs/architecture.md says "every login lands on {@code limbo} first, whatever the phase" -
 *       {@code limbo} applies the resource pack and then sends a plugin message on a {@code nordtal:}
 *       channel meaning "this player is ready", and only then does the proxy connect them onward.
 *       None of that exists: {@code limbo} is a scaffold, there is no pack offer and there is no
 *       plugin-message channel. Routing a {@code PRE_EVENT} login straight to {@code hunger-games}
 *       here would not be implementing that design, it would be inventing a different one and
 *       quietly deleting the pack station. So for the three non-maintenance phases the initial
 *       server stays whatever {@code velocity.toml}'s {@code try} list says, and building the real
 *       thing belongs to the {@code limbo} session.</li>
 * </ul>
 *
 * <h2>When the destination does not exist</h2>
 * {@code gate.yml}'s server names are matched against {@code ProxyServer.getAllServers()}. A name
 * this proxy does not have produces a disconnect, not an undefined state:
 * {@code MAINTENANCE} gets the maintenance screen (the "disconnect" half of the either/or
 * docs/season-phases.md used to leave open, used exactly where holding them is impossible), any
 * other phase gets {@code gate.no-server}. A server that is registered but <em>down</em> cannot be
 * told apart until the connection is attempted; {@link #connect} handles that failure the same way.
 *
 * <h2>Threading</h2>
 * {@link #onPhaseChanged} is called from {@link PhaseWatch}, which refreshes from the poll thread,
 * the {@code LISTEN} thread and the {@code /phase} command. It therefore hands the actual work to
 * the proxy scheduler rather than doing one blocking query per connected player on whichever thread
 * happened to notice the change.
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

    public PlayerRouter(final Object plugin, final ProxyServer proxy, final Logger logger,
                        final AccessDirectory access, final PhaseRouting routing, final PhaseWatch phases,
                        final LoginRoster roster, final FallbackCache fallback, final GateMessages messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.access = Objects.requireNonNull(access, "access");
        this.routing = Objects.requireNonNull(routing, "routing");
        this.phases = Objects.requireNonNull(phases, "phases");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    // ------------------------------------------------------------------ login

    /**
     * Puts a non-admin into {@code limbo} when the network is in {@code MAINTENANCE}, and does
     * nothing at all otherwise.
     * <p>
     * The phase comes from {@link PhaseWatch#lastKnown()} and the admin flag from
     * {@link LoginRoster}, both of which are already in memory. Neither is a second database call:
     * docs/season-phases.md pins the login path to one round trip, and the gate has already spent
     * it. The roster is populated by {@code LoginGate} moments earlier on the same login; a player
     * the roster has never heard of was let in by the fallback cache while the database was
     * unreachable, and is treated as a non-admin, which is the safe way round.
     * </p>
     */
    @Subscribe
    public void onChooseInitialServer(final PlayerChooseInitialServerEvent event) {
        final SeasonPhase phase = phases.lastKnown();
        if (phase != SeasonPhase.MAINTENANCE) {
            // Every other phase keeps velocity.toml's try list until the limbo session builds the
            // pack station and the real login route. See this class's documentation.
            return;
        }

        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        final RouteDecision decision =
                routing.decideAdmitted(phase, roster.isAdmin(uuid), registeredServerNames());

        switch (decision.action()) {
            case CONNECT -> proxy.getServer(decision.server()).ifPresent(event::setInitialServer);
            case STAY -> logger.info("{} is an admin, so the {} phase leaves their initial server "
                    + "alone", player.getUsername(), phase);
            default -> {
                // No limbo to hold them in. Clearing the initial server matters as much as the
                // disconnect: without it Velocity would still try velocity.toml's try list.
                event.setInitialServer(null);
                logger.error("No '{}' server is registered on this proxy, so {} cannot be held in "
                                + "the waiting room during {} and is being disconnected instead",
                        routing.servers().limbo(), player.getUsername(), phase);
                player.disconnect(reasonFor(decision, roster.localeOf(uuid)));
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
            // Same rule as the expiry sweep: a database hiccup must not read as a mass eviction. The
            // player stays where they are and the next poll, expiry pass or login sorts them out.
            logger.warn("Could not re-check {} ({}) while re-routing for a phase change; leaving "
                    + "them where they are", uuid, player.getUsername(), exception);
            return false;
        }
        fallback.remember(uuid, state);
        roster.remember(uuid, state);

        final RouteDecision decision = routing.decide(state, available);
        return switch (decision.action()) {
            case STAY -> false;
            case CONNECT -> connect(player, decision.server(), state.locale());
            default -> {
                logger.info("Disconnecting {} on the phase change: {}", player.getUsername(),
                        decision.action());
                player.disconnect(reasonFor(decision, state.locale()));
                yield true;
            }
        };
    }

    /**
     * Moves one player, unless they are already there.
     * <p>
     * {@code connect()} rather than {@code connectWithIndication()} or {@code fireAndForget()}: a
     * failure here is the case docs/ does not cover - the server is registered and not answering -
     * and leaving the player sitting on a backend the phase says they should not be on is worse
     * than telling them why they cannot get to the right one.
     * </p>
     */
    private boolean connect(final Player player, final String server, final Locale locale) {
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

        player.createConnectionRequest(target).connect().whenComplete((result, error) -> {
            if (error != null) {
                logger.error("Re-routing {} to '{}' failed", player.getUsername(), server, error);
                player.disconnect(messages.noServer(locale));
                return;
            }
            if (result.getStatus() != ConnectionRequestBuilder.Status.SUCCESS
                    && result.getStatus() != ConnectionRequestBuilder.Status.ALREADY_CONNECTED) {
                logger.error("Re-routing {} to '{}' ended as {}", player.getUsername(), server,
                        result.getStatus());
                player.disconnect(messages.noServer(locale));
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

    private Component reasonFor(final RouteDecision decision, final Locale locale) {
        return switch (decision.action()) {
            case REFUSE_UNLINKED -> messages.unlinked(locale);
            case REFUSE_NOT_MEMBER -> messages.notMember(locale);
            // docs/season-phases.md#routing: "with the same message the login gate uses".
            case REFUSE_NO_ACCESS -> messages.noAccess(locale);
            case REFUSE_MAINTENANCE_UNAVAILABLE -> messages.maintenance(locale);
            case REFUSE_NO_SERVER -> messages.noServer(locale);
            case CONNECT, STAY -> throw new IllegalArgumentException(
                    "not a refusal: " + decision.action());
        };
    }
}
