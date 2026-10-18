package eu.nordtal.season.proxy.update;

import static eu.nordtal.season.proxy.ProxyMessages.MESSAGES;

import com.velocitypowered.api.proxy.Player;
import eu.nordtal.season.database.update.RunRecorder;
import eu.nordtal.season.messagerendering.Lines;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.proxy.PhaseServers;
import eu.nordtal.season.proxy.ProxyMessages;
import eu.nordtal.season.proxy.gate.LoginRoster;
import java.time.Duration;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Informs each player whom an update run moved that the player returns. The waiting room sends a chat message. The
 * standby proxy sends a chat message and a countdown subtitle.
 */
public final class Homecoming {

    /** The seconds that the standby proxy announces the return before it happens. */
    public static final Duration NOTICE = Duration.ofSeconds(10);

    /** The display times of the subtitle, equal to those in {@code RestartWatch}. */
    private static final Title.Times TIMES =
            Title.Times.times(Duration.ZERO, Duration.ofMillis(1400), Duration.ofMillis(250));

    private final Logger logger;
    private final MessageRenderer renderer;
    private final LoginRoster roster;
    private final PhaseServers servers;
    private final RunRecorder runs;

    /** The players who were moved and have not been informed. */
    private final Set<UUID> owed = ConcurrentHashMap.newKeySet();

    /** @param runs the store of the move counts */
    public Homecoming(
            final Logger logger,
            final MessageRenderer renderer,
            final LoginRoster roster,
            final PhaseServers servers,
            final RunRecorder runs) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.servers = Objects.requireNonNull(servers, "servers");
    }

    // out of the waiting room

    /** Replaces the set of players to inform with these players. */
    public void movedOut(final Collection<Player> players) {
        final Set<UUID> uuids = new java.util.HashSet<>();
        for (final Player player : players) {
            uuids.add(player.getUniqueId());
        }
        owedNow(uuids);
    }

    /** Sets the players to inform. */
    void owedNow(final Set<UUID> uuids) {
        owed.clear();
        owed.addAll(uuids);
    }

    /** Returns whether the player is to be informed and removes the player from the set. */
    boolean claim(final UUID uuid) {
        return owed.remove(uuid);
    }

    /**
     * Informs the player about the return if an update run moved the player.
     *
     * @param destination the server of the player
     */
    public void comingBack(final Player player, final String destination) {
        if (!claim(player.getUniqueId())) {
            return;
        }
        player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .ifPresent(room -> returned(destination, room, 1));
        try {
            final Locale locale = roster.localeOf(player.getUniqueId());
            player.sendMessage(renderer.format(locale, MESSAGES.returnSection().waitingRoom(serviceName(destination))));
        } catch (final RuntimeException failure) {
            logger.warn(
                    "Could not tell {} that they are being moved back to '{}'",
                    player.getUsername(),
                    destination,
                    failure);
        }
    }

    /** Stores the number of returned players. A failed write is logged. */
    public void returned(final String from, final String to, final int players) {
        try {
            runs.returned(from, to, players);
        } catch (final RuntimeException failure) {
            logger.warn("Could not count {} player(s) back from '{}' to '{}'", players, to, from, failure);
        }
    }

    // off the standby proxy

    /**
     * Sends the announcement. Every player gets the chat message and players outside a waiting room get the subtitle.
     */
    public void say(final Collection<Player> players, final Announcement announcement) {
        for (final Player player : players) {
            try {
                tell(player, announcement);
            } catch (final RuntimeException failure) {
                logger.warn("Could not tell {} that the network is back", player.getUsername(), failure);
            }
        }
    }

    private void tell(final Player player, final Announcement announcement) {
        final Locale locale = roster.localeOf(player.getUniqueId());
        switch (announcement.kind()) {
            case COUNTDOWN -> {
                player.sendMessage(
                        renderer.format(locale, MESSAGES.returnSection().countdown(announcement.seconds())));
                if (isPlaying(player)) {
                    subtitle(player, renderer.format(locale, MESSAGES.restart().tick(announcement.seconds())));
                }
            }
            case TICK -> {
                if (isPlaying(player)) {
                    subtitle(player, renderer.format(locale, MESSAGES.restart().tick(announcement.seconds())));
                }
            }
            case NOW -> {
                player.sendMessage(
                        renderer.format(locale, MESSAGES.returnSection().now()));
                if (isPlaying(player)) {
                    subtitle(
                            player,
                            renderer.format(locale, MESSAGES.returnSection().now()));
                }
            }
            case CANCELLED, FAILED ->
                logger.warn(
                        "The standby was asked to announce {}, which is" + " not something a return can be",
                        announcement.kind());
        }
    }

    /** Returns whether a transfer interrupts the player. */
    private boolean isPlaying(final Player player) {
        return interrupts(
                player.getCurrentServer()
                        .map(connection -> connection.getServerInfo().getName())
                        .orElse(null),
                servers);
    }

    /**
     * Returns whether a transfer interrupts a player on the server.
     *
     * @param server the server of the player, {@code null} while the player connects
     */
    static boolean interrupts(final @Nullable String server, final PhaseServers servers) {
        return server != null && !servers.isWaitingRoom(server);
    }

    private static void subtitle(final Player player, final Component line) {
        player.showTitle(Title.title(Component.empty(), Lines.joined(line), TIMES));
    }

    /** Returns the message with the name of the compose service, or the service name itself without a message. */
    public static MessageRef serviceName(final String service) {
        final ProxyMessages.Restart.What names = MESSAGES.restart().what();
        return switch (service) {
            case "smp" -> names.smp();
            case "limbo" -> names.limbo();
            case "hunger-games" -> names.hungerGames();
            case "proxy" -> names.proxy();
            default -> names.other(service);
        };
    }
}
