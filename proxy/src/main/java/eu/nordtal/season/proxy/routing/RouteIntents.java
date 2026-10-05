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

/**
 * Refuses a connection to any destination {@link PlayerRouter} did not choose, such as Velocity's {@code /server}.
 *
 * Both waiting rooms need no intent: Velocity's own fallback and {@code Evacuation} send players there.
 */
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

    /** Records that this plugin has decided to send {@code player} to {@code server}. */
    public void intend(final UUID player, final String server) {
        if (player != null && server != null) {
            intents.put(player, server);
        }
    }

    /** Refuses a connection this plugin did not ask for; admins are exempt. */
    @Subscribe
    public void onServerPreConnect(final ServerPreConnectEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        if (roster.isAdmin(uuid)) {
            return;
        }

        final String destination = event.getOriginalServer().getServerInfo().getName();
        if (allows(servers, destination, intents.get(uuid))) {
            // Consumed only when the intent allowed it; a waiting room must not eat the real one.
            intents.remove(uuid, destination);
            return;
        }

        // Loud on purpose: every legitimate connection registers an intent.
        logger.warn(
                "Refused to connect {} to '{}': nothing in proxy chose that "
                        + "destination. If this is a route this plugin takes, it is missing its "
                        + "RouteIntents#intend call.",
                player.getUsername(),
                destination);
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
    }

    /**
     * The decision, without Velocity.
     *
     * @param intended where this plugin last decided to send the player, or {@code null}
     */
    static boolean allows(final PhaseServers servers, final String destination, final @Nullable String intended) {
        return servers.isWaitingRoom(destination) || destination.equals(intended);
    }

    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        intents.remove(event.getPlayer().getUniqueId());
    }

    /** How many players hold an intent, for a test and a log line. */
    public int size() {
        return intents.size();
    }
}
