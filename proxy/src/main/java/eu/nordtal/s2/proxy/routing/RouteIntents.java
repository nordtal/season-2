package eu.nordtal.s2.proxy.routing;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import eu.nordtal.s2.proxy.PhaseServers;
import eu.nordtal.s2.proxy.PlayerRouter;
import eu.nordtal.s2.proxy.gate.LoginRoster;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Where {@link PlayerRouter} decided to send somebody - and the refusal of every other destination.
 *
 * Routing was a decision nothing enforced. {@link PlayerRouter} works out where a player belongs
 * from the phase, their access and the pack station, and then asks Velocity to connect them - but
 * any other path to a connection went through unexamined, and there was one: Velocity's own
 * {@code /server}, which is open to every player because its permission check refuses only on an
 * explicit {@code FALSE} and nothing ever set one. A player could type {@code /server hunger-games}
 * during the SMP phase and be there, past the phase, past the access check on that phase's server,
 * and past the resource pack.
 *
 * {@code CommandGate} refuses that command and hides it, and this is the layer underneath: it
 * asks not "may you type that" but "did anything in this plugin actually choose this destination".
 * Two layers because they fail differently - a command filter is a list somebody edits, and this is
 * a fact about what the code did.
 *
 * The waiting room is allowed without an intent because it is where an unrouted player belongs,
 * and because it is where Velocity itself sends anybody it has to move: the {@code try} list. A
 * player kicked from a backend has their fallback connection attempted by the proxy core, not by
 * this plugin, so it carries no intent - and refusing it would turn "the SMP restarted" into
 * "everybody was disconnected". The waiting room is the one destination where arriving without a
 * decision costs nothing: the pack station is standing there, and it releases them through
 * {@link PlayerRouter} like any other arrival.
 *
 * Both waiting rooms count, not only {@code gate.yml#server-limbo}: {@code Evacuation} moves
 * players with a bare {@code fireAndForget} and registers no intent - it does not need one, the
 * destination is a waiting room - so an evacuation into {@code limbo-standby} must not be refused
 * here for every non-admin on the network. It is asked through {@link PhaseServers#isWaitingRoom}
 * for that reason.
 *
 * An intent is consumed, and outliving one is not an error: a connection request that fails leaves
 * its intent behind; the next one for the same player replaces it. Both are fine, because an intent
 * is only ever a permission to go somewhere this plugin has already decided on, and the decision is
 * re-taken on every route. They are dropped on disconnect so that the map is the size of the player
 * list rather than of the session log.
 */
public final class RouteIntents {

    private final LoginRoster roster;
    private final PhaseServers servers;
    private final Logger logger;

    private final ConcurrentHashMap<UUID, String> intents = new ConcurrentHashMap<>();

    /**
     * @param roster  who is an admin, from the login query - a map lookup and never a query, the
     *                same source {@code CommandGate} uses
     * @param servers the backend names, for the two destinations that need no intent - see the
     *                class comment on why it is two and not one
     */
    public RouteIntents(final LoginRoster roster, final PhaseServers servers, final Logger logger) {
        this.roster = Objects.requireNonNull(roster, "roster");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Records that this plugin has decided to send {@code player} to {@code server}. */
    public void intend(final UUID player, final String server) {
        if (player != null && server != null) {
            intents.put(player, server);
        }
    }

    /**
     * Refuses a connection this plugin did not ask for.
     *
     * Admins are exempt, for the same reason they are exempt from the command allowlist: they are
     * the people who move around the network to fix it, and {@code /server} is how they do it.
     */
    @Subscribe
    public void onServerPreConnect(final ServerPreConnectEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        if (roster.isAdmin(uuid)) {
            return;
        }

        final String destination = event.getOriginalServer().getServerInfo().getName();
        if (allows(servers, destination, intents.get(uuid))) {
            // Consumed only when the intent allowed it; a waiting room needs none and must not eat the real one.
            intents.remove(uuid, destination);
            return;
        }

        // Loud on purpose: every legitimate connection here registers an intent, so a missing one is worth finding.
        logger.warn(
                "Refused to connect {} to '{}': nothing in proxy chose that "
                        + "destination. If this is a route this plugin takes, it is missing its "
                        + "RouteIntents#intend call.",
                player.getUsername(),
                destination);
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
    }

    /**
     * The decision, without Velocity - the same split {@code BackendKick#decide} has, for the same reason.
     *
     * A rule that needs a running proxy to be exercised is a rule nothing holds.
     *
     * @param servers     the backend names
     * @param destination where the connection is going
     * @param intended    where this plugin last decided to send the player, or {@code null}
     * @return whether the connection may go through
     */
    static boolean allows(final PhaseServers servers, final String destination, final @Nullable String intended) {
        return servers.isWaitingRoom(destination) || destination.equals(intended);
    }

    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        intents.remove(event.getPlayer().getUniqueId());
    }

    /** How many players currently hold one. For a test and for a log line; never a decision. */
    public int size() {
        return intents.size();
    }
}
