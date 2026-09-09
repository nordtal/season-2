package eu.nordtal.s2.networkcontrol.routing;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;

import eu.nordtal.s2.networkcontrol.gate.LoginRoster;

import org.slf4j.Logger;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where {@link PlayerRouter} decided to send somebody - and the refusal of every other destination.
 *
 * <h2>What this closes</h2>
 * Routing was a decision nothing enforced. {@link PlayerRouter} works out where a player belongs
 * from the phase, their access and the pack station, and then asks Velocity to connect them - but
 * <em>any</em> other path to a connection went through unexamined, and there was one: Velocity's own
 * {@code /server}, which is open to every player because its permission check refuses only on an
 * explicit {@code FALSE} and nothing ever set one. A player could type {@code /server hunger-games}
 * during the SMP phase and be there, past the phase, past the access check on that phase's server,
 * and past the resource pack.
 *
 * <p>{@code CommandGate} refuses that command and hides it, and this is the layer underneath: it
 * asks not "may you type that" but "did anything in this plugin actually choose this destination".
 * Two layers because they fail differently - a command filter is a list somebody edits, and this is
 * a fact about what the code did.</p>
 *
 * <h2>Why the waiting room is allowed without an intent</h2>
 * Because it is where an unrouted player belongs, and because it is where Velocity itself sends
 * anybody it has to move: the {@code try} list. A player kicked from a backend has their fallback
 * connection attempted by the proxy core, not by this plugin, so it carries no intent - and refusing
 * it would turn "the SMP restarted" into "everybody was disconnected". The waiting room is the one
 * destination where arriving without a decision costs nothing: the pack station is standing there,
 * and it releases them through {@link PlayerRouter} like any other arrival.
 *
 * <h2>An intent is consumed, and outliving one is not an error</h2>
 * A connection request that fails leaves its intent behind; the next one for the same player
 * replaces it. Both are fine, because an intent is only ever a permission to go somewhere this
 * plugin has already decided on, and the decision is re-taken on every route. They are dropped on
 * disconnect so that the map is the size of the player list rather than of the session log.
 */
public final class RouteIntents {

    private final LoginRoster roster;
    private final String waitingRoom;
    private final Logger logger;

    private final ConcurrentHashMap<UUID, String> intents = new ConcurrentHashMap<>();

    /**
     * @param roster      who is an admin, from the login query - a map lookup and never a query,
     *                    the same source {@code CommandGate} uses
     * @param waitingRoom {@code gate.yml#server-limbo}, the one destination that needs no intent
     */
    public RouteIntents(final LoginRoster roster, final String waitingRoom, final Logger logger) {
        this.roster = Objects.requireNonNull(roster, "roster");
        this.waitingRoom = Objects.requireNonNull(waitingRoom, "waitingRoom");
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
     * <p>Admins are exempt, for the same reason they are exempt from the command allowlist: they are
     * the people who move around the network to fix it, and {@code /server} is how they do it.</p>
     */
    @Subscribe
    public void onServerPreConnect(final ServerPreConnectEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        if (roster.isAdmin(uuid)) {
            return;
        }

        final String destination = event.getOriginalServer().getServerInfo().getName();
        if (destination.equals(waitingRoom)) {
            return;
        }
        if (destination.equals(intents.get(uuid))) {
            intents.remove(uuid);
            return;
        }

        // Loud on purpose. Every legitimate connection in this plugin registers an intent, so a
        // line here is either somebody going around the routing - which is what it is for - or a
        // connection path that was added without one, which is a bug that has just cost a player
        // their destination. Both need to be findable in the log without knowing to look.
        logger.warn("Refused to connect {} to '{}': nothing in network-control chose that "
                + "destination. If this is a route this plugin takes, it is missing its "
                + "RouteIntents#intend call.", player.getUsername(), destination);
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
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
