package eu.nordtal.season.proxy.update;

import com.velocitypowered.api.proxy.Player;
import eu.nordtal.season.database.update.RunRecorder;
import eu.nordtal.season.messagerendering.Lines;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.proxy.PhaseServers;
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
 * Informs each player whom an update run moved that the player returns.
 * The waiting room sends a chat message. The standby proxy sends a chat message and a countdown subtitle.
 */
public final class Homecoming {

    /** The service a text names: the network, one of the season's services, or another by its compose name. */
    public enum Service {
        NETWORK,
        SMP,
        LIMBO,
        HUNGER_GAMES,
        PROXY,
        OTHER;

        /** @param service the compose service, {@code null} for the whole network */
        static Service of(final @Nullable String service) {
            if (service == null) {
                return NETWORK;
            }
            return switch (service) {
                case "smp" -> SMP;
                case "limbo" -> LIMBO;
                case "hunger-games" -> HUNGER_GAMES;
                case "proxy" -> PROXY;
                default -> OTHER;
            };
        }
    }

    /** @param service the compose service, which the text names when {@code what} is {@code OTHER} */
    record Named(Service what, @Example("steward") String service) {}

    record Seconds(@Example("10") long seconds) {}

    static final DocumentSpec<Named> MOVED_OUT =
            chat("return-moved-out", Named.class).titled("Moved to the waiting room");
    static final DocumentSpec<Named> WAITING_ROOM =
            chat("return-waiting-room", Named.class).titled("Server back, leaving the waiting room");
    static final DocumentSpec<Seconds> COUNTDOWN =
            chat("return-countdown", Seconds.class).titled("Return countdown");
    static final DocumentSpec<DocumentSpec.None> NOW =
            chat("return-now", DocumentSpec.None.class).titled("Return now");

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

    /** The run that moved the players in {@link #owed}. */
    private long owedFor = -1;

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

    /**
     * Tells the player why the run moves them, once per run, and adds them to the players to inform on return.
     *
     * @param from the server the run stops under the player
     * @return whether the player was not moved by this run before
     */
    public boolean movedOut(final long run, final Player player, final String from) {
        if (!owe(run, player.getUniqueId())) {
            return false;
        }
        try {
            final Locale locale = roster.localeOf(player.getUniqueId());
            player.sendMessage(renderer.format(locale, MOVED_OUT.with(named(from))));
        } catch (final RuntimeException failure) {
            logger.warn("Could not tell {} why they are moved off '{}'", player.getUsername(), from, failure);
        }
        return true;
    }

    /**
     * Adds the player to the players to inform. A new run replaces the players of the run before.
     *
     * @return whether the player was not owed for this run yet
     */
    synchronized boolean owe(final long run, final UUID uuid) {
        if (run != owedFor) {
            owed.clear();
            owedFor = run;
        }
        return owed.add(uuid);
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
            player.sendMessage(renderer.format(locale, WAITING_ROOM.with(named(destination))));
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
                player.sendMessage(renderer.format(locale, COUNTDOWN.with(new Seconds(announcement.seconds()))));
                if (isPlaying(player)) {
                    tick(player, locale, announcement.seconds());
                }
            }
            case TICK -> {
                if (isPlaying(player)) {
                    tick(player, locale, announcement.seconds());
                }
            }
            case NOW -> {
                final Component now = renderer.format(locale, Sent.of(NOW));
                player.sendMessage(now);
                if (isPlaying(player)) {
                    player.showTitle(Title.title(Component.empty(), Lines.joined(now), TIMES));
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

    /** Shows the seconds left as a subtitle, as {@code RestartWatch} does. */
    private void tick(final Player player, final Locale locale, final long seconds) {
        player.showTitle(renderer.title(locale, RestartWatch.TICK.with(new RestartWatch.Tick(seconds)), TIMES));
    }

    private static Named named(final String service) {
        return new Named(Service.of(service), service);
    }

    private static <V extends Record> DocumentSpec<V> chat(final String name, final Class<V> values) {
        return DocumentSpec.game("proxy", name, "Restart", DocumentSpec.Shape.CHAT, values);
    }
}
