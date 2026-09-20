package eu.nordtal.s2.proxy.update;

import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.proxy.gate.LoginRoster;
import eu.nordtal.s2.proxy.routing.PhaseServers;

import com.velocitypowered.api.proxy.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The way back gets a voice - season-2-ops/118, point 3.
 *
 * <h2>Why the return needed one at all</h2>
 * Everything this network says is said on the way out. Till, 2026-09-20: <i>when they are
 * transferred back there should be a message too. Coming back out of the limbo or the standby limbo,
 * a chat line shortly beforehand is enough - if the proxy is swapped and the player is already on
 * the SMP, that additionally needs a countdown, because it interrupts gameplay.</i> So there are two
 * returns here and they are weighted differently, which is the whole design:
 *
 * <ul>
 *   <li><b>Out of the waiting room.</b> One chat line, immediately before the connection. Somebody
 *       sitting in the limbo is doing nothing that can be interrupted, and a countdown to the end of
 *       a wait would be ten more seconds of waiting.</li>
 *   <li><b>Off the standby proxy.</b> Chat <em>and</em> a counted subtitle, because the player is
 *       standing on the SMP with their own hands full - the transfer lands in the middle of
 *       whatever they are doing, which is exactly the reason the way out has a countdown.</li>
 * </ul>
 *
 * <h2>The register, and why it is not every release</h2>
 * {@code limbo} is where <em>every</em> login waits, so "is being released from the waiting room"
 * is not the same question as "is coming back from a run". Only players {@link Evacuation} moved
 * are owed the sentence, and each is owed it once: {@link #movedOut} writes them down and
 * {@link #comingBack} spends it. A release that fails leaves nothing behind - the player stays in
 * the room and the next attempt is silent - which is the deliberately quiet direction. One line
 * that was swallowed is better than one every ten seconds for as long as a backend is down.
 */
public final class Homecoming {

    /**
     * How long the standby proxy warns before it hands the network back.
     *
     * <p>Ten, and not the sixty of {@code UpdateDirectory.UPDATE_COUNTDOWN}: the two are different
     * promises. Sixty seconds of warning exist so that somebody can get out of a cave before the
     * server stops; a return costs nothing but a loading screen, and the whole point of the swap is
     * that it is over quickly. Ten is also exactly the stretch {@code Countdown} already draws one
     * subtitle per second for, so the return counts in the same voice the way out does.</p>
     */
    public static final Duration NOTICE = Duration.ofSeconds(10);

    /** As long as a tick's subtitle in {@code RestartWatch}, and for the reason given there. */
    private static final Title.Times TIMES = Title.Times.times(
            Duration.ZERO, Duration.ofMillis(1400), Duration.ofMillis(250));

    private final Logger logger;
    private final Messages messages;
    private final LoginRoster roster;
    private final PhaseServers servers;

    /**
     * Who was moved out of the way and has not been told they are going back.
     *
     * <p>Emptied at the start of every evacuation rather than swept: what is in it is the last
     * run's moved players, so a player who disconnected in the waiting room costs one UUID until
     * the next run and nothing after it.</p>
     */
    private final Set<UUID> owed = ConcurrentHashMap.newKeySet();

    public Homecoming(final Logger logger, final Messages messages, final LoginRoster roster,
                      final PhaseServers servers) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.servers = Objects.requireNonNull(servers, "servers");
    }

    // ------------------------------------------------------------------ out of the waiting room

    /** A new evacuation: these are the players it moved, and nobody else is owed anything. */
    public void movedOut(final Collection<Player> players) {
        final Set<UUID> uuids = new java.util.HashSet<>();
        for (final Player player : players) {
            uuids.add(player.getUniqueId());
        }
        owedNow(uuids);
    }

    /** The register, without Velocity: what an evacuation leaves behind. */
    void owedNow(final Set<UUID> uuids) {
        owed.clear();
        owed.addAll(uuids);
    }

    /** @return whether this player is owed the sentence - and if so, never again */
    boolean claim(final UUID uuid) {
        return owed.remove(uuid);
    }

    /**
     * A player the waiting room is about to release. Says so, once, if a run put them there.
     *
     * <p>Said <em>before</em> the connection is asked for rather than after it succeeds: after it
     * succeeds they are already standing on their own server and the sentence has become a
     * greeting. Till asked for shortly beforehand, and the chat line and the loading screen
     * arriving together is what that looks like from a client.</p>
     *
     * @param player      the player being released
     * @param destination the server they are going to, for the name in the line
     */
    public void comingBack(final Player player, final String destination) {
        if (!claim(player.getUniqueId())) {
            return;
        }
        try {
            final Locale locale = roster.localeOf(player.getUniqueId());
            player.sendMessage(MessageRenderer.of(messages).format(locale, "return.waiting-room",
                    Map.of("what", serviceName(messages, locale, destination))));
        } catch (final RuntimeException failure) {
            logger.warn("Could not tell {} that they are being moved back to '{}'",
                    player.getUsername(), destination, failure);
        }
    }

    // ------------------------------------------------------------------ off the standby proxy

    /**
     * One beat of the standby proxy's return, spoken to the players it is holding.
     *
     * <p>The chat half goes to everybody and the subtitle only to the players who are not sitting
     * in a waiting room. That split is Till's distinction exactly: a counter in the middle of the
     * screen is for somebody whose game is about to be interrupted, and a black screen with a
     * "please wait" on it is not interrupted by anything.</p>
     */
    public void say(final Collection<Player> players, final Announcement announcement) {
        for (final Player player : players) {
            try {
                tell(player, announcement);
            } catch (final RuntimeException failure) {
                logger.warn("Could not tell {} that the network is back",
                        player.getUsername(), failure);
            }
        }
    }

    private void tell(final Player player, final Announcement announcement) {
        final Locale locale = roster.localeOf(player.getUniqueId());
        final MessageRenderer renderer = MessageRenderer.of(messages);
        switch (announcement.kind()) {
            case COUNTDOWN -> {
                player.sendMessage(renderer.format(locale, "return.countdown",
                        "seconds", announcement.seconds()));
                if (isPlaying(player)) {
                    subtitle(player, renderer.format(locale, "restart.tick",
                            "seconds", announcement.seconds()));
                }
            }
            case TICK -> {
                if (isPlaying(player)) {
                    subtitle(player, renderer.format(locale, "restart.tick",
                            "seconds", announcement.seconds()));
                }
            }
            case NOW -> {
                player.sendMessage(renderer.get(locale, "return.now"));
                if (isPlaying(player)) {
                    subtitle(player, renderer.get(locale, "return.now"));
                }
            }
            // Neither can happen on this path: the standby speaks only about a return it has
            // already decided on, and it decides by looking at a port rather than at a row that
            // could be withdrawn. Named rather than defaulted, so a future kind is a compile error
            // here instead of silence on somebody's screen.
            case CANCELLED, FAILED -> logger.warn("The standby was asked to announce {}, which is"
                    + " not something a return can be", announcement.kind());
        }
    }

    /** @return whether this player is doing something a transfer would interrupt */
    private boolean isPlaying(final Player player) {
        return interrupts(player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null), servers);
    }

    /**
     * The rule behind the subtitle, without a player in it.
     *
     * @param server where they are standing, or {@code null} while they are on no backend at all -
     *               which on the standby proxy is somebody still connecting, and a counter is no
     *               use to them
     * @return whether a transfer would interrupt something
     */
    static boolean interrupts(final String server, final PhaseServers servers) {
        return server != null && !servers.isWaitingRoom(server);
    }

    private static void subtitle(final Player player, final Component line) {
        player.showTitle(Title.title(Component.empty(), line, TIMES));
    }

    /**
     * A compose service name as a player would say it, or the compose name itself when there is no
     * line for it.
     *
     * <p>Shared with {@code RestartWatch}, which asks the same question of the service a run is
     * about: two answers to one question would be two different names for the SMP in two messages
     * about the same run. <b>Asked of English</b> and not of the player's own locale - every
     * language falls back to English, so asking the locale would drop a perfectly good English name
     * in favour of a compose name for anything not yet translated.</p>
     */
    public static Component serviceName(final Messages messages, final Locale locale,
                                        final String service) {
        final String key = "restart.what." + service;
        return messages.hasTranslation(Locale.ENGLISH, key)
                ? MessageRenderer.of(messages).get(locale, key)
                : Component.text(service);
    }
}
