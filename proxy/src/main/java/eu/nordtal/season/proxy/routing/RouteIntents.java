package eu.nordtal.season.proxy.routing;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import eu.nordtal.season.proxy.PhaseServers;
import eu.nordtal.season.proxy.PlayerRouter;
import eu.nordtal.season.proxy.gate.LoginRoster;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** Refuses a connection to a server that {@link PlayerRouter} did not choose. The waiting rooms need no intent. */
public final class RouteIntents {

    private final LoginRoster roster;
    private final PhaseServers servers;
    private final Logger logger;

    private final ConcurrentHashMap<UUID, String> intents = new ConcurrentHashMap<>();

    public RouteIntents(final LoginRoster roster, final PhaseServers servers, final Logger logger) {
        this.roster = Objects.requireNonNull(roster, "roster");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Records that the player is to connect to the server. */
    public void intend(final UUID player, final String server) {
        if (player != null && server != null) {
            intents.put(player, server);
        }
    }

    /** Refuses a connection without an intent. Admins are exempt. */
    @Subscribe
    public void onServerPreConnect(final ServerPreConnectEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        if (roster.isAdmin(uuid)) {
            return;
        }

        final String destination = event.getOriginalServer().getServerInfo().getName();
        if (allows(servers, destination, intents.get(uuid))) {
            intents.remove(uuid, destination);
            return;
        }

        logger.warn(
                "Refused to connect {} to '{}': nothing in proxy chose that "
                        + "destination. If this is a route this plugin takes, it is missing its "
                        + "RouteIntents#intend call.",
                player.getUsername(),
                destination);
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
    }

    /**
     * Returns whether the connection is allowed.
     *
     * @param intended the server that the player is to connect to, {@code null} without an intent
     */
    static boolean allows(final PhaseServers servers, final String destination, final @Nullable String intended) {
        return servers.isWaitingRoom(destination) || destination.equals(intended);
    }

    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        intents.remove(event.getPlayer().getUniqueId());
    }

    /** Returns the number of intents. */
    public int size() {
        return intents.size();
    }
}
