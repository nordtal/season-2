package eu.nordtal.s2.proxy.update;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.s2.common.time.CountdownPlan;
import eu.nordtal.s2.proxy.ProxyRole;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The standby proxy's half of a swap: it holds the network, and sends it home by itself.
 *
 * Players go home after an outage plus {@link #SETTLE}, or unconditionally after {@link #RESCUE_AFTER}.
 */
public final class StandbyReturn {

    /** How often this runs; short, because a person is looking at a loading screen. */
    public static final Duration INTERVAL = Duration.ofSeconds(2);

    /** How long to wait for the handshake; short, since it is a port on the same machine. */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(1);

    /** How long the public address must have answered before players are sent home without an outage seen. */
    static final Duration RESCUE_AFTER = Duration.ofMinutes(1);

    /** How long it must have answered after an outage; longer than one tick, so a release takes two passes. */
    static final Duration SETTLE = Duration.ofSeconds(4);

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final SwapStore seats;
    private final ProxyRole role;
    private final InetSocketAddress home;
    private final Clock clock;
    private final Probe probe;

    private final Homecoming voice;

    /** The same planner the way out uses, so the return counts in the same voice. */
    private final Countdown notice = new Countdown();

    /** A new number per return, because {@link Countdown} plans one row once. */
    private long notices;

    /** Whether a return is promised; once set, a flickering probe cannot start a second countdown. */
    private boolean returning;

    /** Whether the public address has refused a connection since this proxy last had players. */
    private boolean outageSeen;

    /** When it started answering again, or {@code null} while it is not. */
    private @Nullable Instant answeringSince;

    public StandbyReturn(
            final Object plugin,
            final ProxyServer proxy,
            final Logger logger,
            final SwapStore seats,
            final ProxyRole role,
            final InetSocketAddress home,
            final Clock clock,
            final Homecoming voice) {
        this(plugin, proxy, logger, seats, role, home, clock, voice, StandbyReturn::connects);
    }

    /**
     * Takes the probe as a seam, so the decision is testable without binding a port.
     *
     * @param probe how to ask whether an address answers
     */
    StandbyReturn(
            final Object plugin,
            final ProxyServer proxy,
            final Logger logger,
            final SwapStore seats,
            final ProxyRole role,
            final InetSocketAddress home,
            final Clock clock,
            final Homecoming voice,
            final Probe probe) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.voice = Objects.requireNonNull(voice, "voice");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.seats = Objects.requireNonNull(seats, "seats");
        this.role = Objects.requireNonNull(role, "role");
        this.home = home;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    /** Returns whether this process will do anything at all, for the startup log line. */
    public boolean isArmed() {
        return home != null && role.isStandby();
    }

    /** One pass: reports how many players are here and sends them home if it is time; never throws. */
    public void check() {
        if (!isArmed()) {
            return;
        }
        final Collection<Player> players = proxy.getAllPlayers();
        final Instant now = clock.instant();

        try {
            // Always, including zero: no row has to keep meaning this standby has never spoken.
            seats.reportStandby(players.size(), now);
        } catch (final RuntimeException failure) {
            logger.warn("Could not report how many players the standby is holding", failure);
        }

        if (returning) {
            // Already promised and scheduled: nothing this pass observes improves on it.
            return;
        }
        if (players.isEmpty()) {
            // Nobody to protect: the next group to arrive has to see its own outage.
            outageSeen = false;
            answeringSince = null;
            return;
        }

        final boolean answers;
        try {
            answers = probe.answers(home, PROBE_TIMEOUT);
        } catch (final RuntimeException failure) {
            logger.warn("Could not probe {}; holding the players here this pass", home, failure);
            return;
        }

        if (!answers) {
            if (!outageSeen) {
                logger.info(
                        "{} has stopped answering - this is the swap. {} player(s) stay here" + " until it is back",
                        home,
                        players.size());
            }
            outageSeen = true;
            answeringSince = null;
            return;
        }
        if (answeringSince == null) {
            answeringSince = now;
        }
        if (!releases(outageSeen, Duration.between(answeringSince, now))) {
            return;
        }

        announceThenSendHome(players);
    }

    /** Tells the players the network is back, counts the last ten seconds, and transfers them on zero. */
    private void announceThenSendHome(final Collection<Player> players) {
        returning = true;
        final List<CountdownPlan.Beat<Announcement>> beats =
                notice.beats(++notices, Homecoming.NOTICE).orElseGet(List::of);
        logger.info(
                "{} is answering again: telling {} player(s) and handing them back in {}s",
                home,
                players.size(),
                Homecoming.NOTICE.toSeconds());

        for (final CountdownPlan.Beat<Announcement> beat : beats) {
            proxy.getScheduler()
                    .buildTask(plugin, () -> {
                        // Asked again per beat: a player who logged out is gone, one who logged in is owed the same.
                        final Collection<Player> here = proxy.getAllPlayers();
                        voice.say(here, beat.said());
                        if (beat.said().kind() != Announcement.Kind.NOW) {
                            return;
                        }
                        // Sentence before transfer, unlike the way out: after it, the message reaches nobody.
                        sendHome(here);
                        returning = false;
                        outageSeen = false;
                        answeringSince = null;
                    })
                    .delay(beat.delay())
                    .schedule();
        }
    }

    /**
     * The decision, without a socket and without Velocity.
     *
     * @param outageSeen whether the public address has refused a connection since these players arrived
     * @param answering how long it has been answering without interruption
     * @return whether to send the players home now
     */
    static boolean releases(final boolean outageSeen, final Duration answering) {
        if (outageSeen) {
            return answering.compareTo(SETTLE) >= 0;
        }
        // No outage means this was called off, or somebody joined this port by hand.
        return answering.compareTo(RESCUE_AFTER) >= 0;
    }

    private void sendHome(final Collection<Player> players) {
        logger.info("Transferring {} player(s) back to {}", players.size(), home);
        for (final Player player : players) {
            try {
                player.transferToHost(home);
            } catch (final RuntimeException failure) {
                // One client that cannot be transferred must not cost the rest; the next pass tries again.
                logger.warn("Could not transfer {} back to {}", player.getUsername(), home, failure);
            }
        }
    }

    /** Whether an address accepts a TCP connection. */
    @FunctionalInterface
    interface Probe {

        /**
         * Knocks once.
         *
         * @param address where to knock
         * @param timeout how long to wait for the handshake
         * @return whether it answered
         */
        boolean answers(InetSocketAddress address, Duration timeout);
    }

    /** The real probe; it resolves the unresolved address here, and a name that does not resolve is not answering. */
    static boolean connects(final InetSocketAddress address, final Duration timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address.getHostString(), address.getPort()), (int) timeout.toMillis());
            return true;
        } catch (final IOException | RuntimeException refused) {
            return false;
        }
    }
}
