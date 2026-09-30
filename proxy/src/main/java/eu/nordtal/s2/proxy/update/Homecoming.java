package eu.nordtal.s2.proxy.update;

import static eu.nordtal.s2.proxy.ProxyMessages.MESSAGES;

import com.velocitypowered.api.proxy.Player;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.proxy.PhaseServers;
import eu.nordtal.s2.proxy.ProxyMessages;
import eu.nordtal.s2.proxy.gate.LoginRoster;
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
 * Tells players moved out by an update that they are going back, once each.
 *
 * From the waiting room: one chat line. From the standby proxy: chat plus a counted subtitle.
 */
public final class Homecoming {

    /**
     * How long the standby proxy warns before it hands the network back, matching {@code Countdown}'s subtitle stretch.
     */
    public static final Duration NOTICE = Duration.ofSeconds(10);

    /** As long as a tick's subtitle in {@code RestartWatch}. */
    private static final Title.Times TIMES =
            Title.Times.times(Duration.ZERO, Duration.ofMillis(1400), Duration.ofMillis(250));

    private final Logger logger;
    private final Messages messages;
    private final LoginRoster roster;
    private final PhaseServers servers;

    /** Players moved out of the way and not yet told they are going back; emptied at the start of every evacuation. */
    private final Set<UUID> owed = ConcurrentHashMap.newKeySet();

    public Homecoming(
            final Logger logger, final Messages messages, final LoginRoster roster, final PhaseServers servers) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.servers = Objects.requireNonNull(servers, "servers");
    }

    // out of the waiting room

    /** Starts a new evacuation: these players are owed the sentence, and nobody else. */
    public void movedOut(final Collection<Player> players) {
        final Set<UUID> uuids = new java.util.HashSet<>();
        for (final Player player : players) {
            uuids.add(player.getUniqueId());
        }
        owedNow(uuids);
    }

    /** Sets the register directly, without Velocity. */
    void owedNow(final Set<UUID> uuids) {
        owed.clear();
        owed.addAll(uuids);
    }

    /** Returns whether this player is owed the sentence, and spends it if so. */
    boolean claim(final UUID uuid) {
        return owed.remove(uuid);
    }

    /**
     * Tells a player the waiting room is about to release that they are going back, once, if a run put them there.
     *
     * @param player the player being released
     * @param destination the server they are going to, for the name in the line
     */
    public void comingBack(final Player player, final String destination) {
        if (!claim(player.getUniqueId())) {
            return;
        }
        try {
            final Locale locale = roster.localeOf(player.getUniqueId());
            player.sendMessage(MessageRenderer.of(messages)
                    .format(locale, MESSAGES.returnSection().waitingRoom(serviceName(messages, locale, destination))));
        } catch (final RuntimeException failure) {
            logger.warn(
                    "Could not tell {} that they are being moved back to '{}'",
                    player.getUsername(),
                    destination,
                    failure);
        }
    }

    // off the standby proxy

    /**
     * Speaks one beat of the standby's return: chat to everybody, the subtitle only to players outside a waiting room.
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
        final MessageRenderer renderer = MessageRenderer.of(messages);
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
            // Neither can happen: the standby only speaks about a return it already decided on.
            case CANCELLED, FAILED ->
                logger.warn(
                        "The standby was asked to announce {}, which is" + " not something a return can be",
                        announcement.kind());
        }
    }

    /** Returns whether a transfer would interrupt what this player is doing. */
    private boolean isPlaying(final Player player) {
        return interrupts(
                player.getCurrentServer()
                        .map(connection -> connection.getServerInfo().getName())
                        .orElse(null),
                servers);
    }

    /**
     * Returns whether a transfer would interrupt a player standing on {@code server}.
     *
     * @param server where they are standing, or {@code null} while still connecting
     */
    static boolean interrupts(final @Nullable String server, final PhaseServers servers) {
        return server != null && !servers.isWaitingRoom(server);
    }

    private static void subtitle(final Player player, final Component line) {
        player.showTitle(Title.title(Component.empty(), line, TIMES));
    }

    /**
     * A compose service name as a player would say it, or the compose name when there is no line for it.
     *
     * Always read in English, since every locale falls back to English and would otherwise lose the name.
     */
    public static Component serviceName(final Messages messages, final Locale locale, final String service) {
        final ProxyMessages.Restart.What names = MESSAGES.restart().what();
        final MessageRef name = switch (service) {
            case "smp" -> names.smp();
            case "limbo" -> names.limbo();
            case "hunger-games" -> names.hungerGames();
            case "proxy" -> names.proxy();
            default -> null;
        };
        return name == null
                ? Component.text(service)
                : MessageRenderer.of(messages).format(locale, name);
    }
}
