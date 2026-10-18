package eu.nordtal.season.proxy.update;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.season.common.time.CountdownPlan;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.proxy.ProxyRole;
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
 * Transfers the players of the standby proxy back to the live proxy.
 * It waits {@link #SETTLE} after an outage and {@link #RESCUE_AFTER} without an outage.
 */
public final class StandbyReturn {

    /** The interval between two passes. */
    public static final Duration INTERVAL = Duration.ofSeconds(2);

    /** The time that the live proxy has to answer a probe. */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(1);

    /** The answer time of the live proxy that sends the players back without an outage. */
    static final Duration RESCUE_AFTER = Duration.ofMinutes(1);

    /** The answer time of the live proxy that sends the players back after an outage. */
    static final Duration SETTLE = Duration.ofSeconds(4);

    private final Scheduler scheduler;
    private final ProxyServer proxy;
    private final Logger logger;
    private final SwapStore seats;
    private final ProxyRole role;
    private final @Nullable InetSocketAddress home;
    private final @Nullable InetSocketAddress live;
    private final Clock clock;
    private final Probe probe;

    private final Homecoming voice;

    /** Plans the announcements of the return. */
    private final Countdown notice = new Countdown();

    /** The id of the current return. */
    private long notices;

    /** Whether a return is planned. */
    private boolean returning;

    /** Whether the live proxy refused a connection since the players arrived. */
    private boolean outageSeen;

    /** The time since which the live proxy answers, {@code null} while it does not. */
    private @Nullable Instant answeringSince;

    public StandbyReturn(
            final Scheduler scheduler,
            final ProxyServer proxy,
            final Logger logger,
            final SwapStore seats,
            final ProxyRole role,
            final @Nullable InetSocketAddress home,
            final @Nullable InetSocketAddress live,
            final Clock clock,
            final Homecoming voice) {
        this(scheduler, proxy, logger, seats, role, home, live, clock, voice, StandbyReturn::connects);
    }

    /**
     * @param live the address of the live proxy itself, not the public address
     * @param probe asks whether an address answers
     */
    StandbyReturn(
            final Scheduler scheduler,
            final ProxyServer proxy,
            final Logger logger,
            final SwapStore seats,
            final ProxyRole role,
            final @Nullable InetSocketAddress home,
            final @Nullable InetSocketAddress live,
            final Clock clock,
            final Homecoming voice,
            final Probe probe) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.voice = Objects.requireNonNull(voice, "voice");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.seats = Objects.requireNonNull(seats, "seats");
        this.role = Objects.requireNonNull(role, "role");
        this.home = home;
        this.live = live;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    /** Returns whether this proxy is a standby with both addresses. */
    public boolean isArmed() {
        return home != null && live != null && role.isStandby();
    }

    /** Reports the number of players and transfers them back when the time has come. It never throws. */
    public void check() {
        final InetSocketAddress target = live;
        if (target == null || !isArmed()) {
            return;
        }
        final Collection<Player> players = proxy.getAllPlayers();
        final Instant now = clock.instant();

        try {
            seats.reportStandby(players.size(), now);
        } catch (final RuntimeException failure) {
            logger.warn("Could not report how many players the standby is holding", failure);
        }

        if (returning) {
            return;
        }
        if (players.isEmpty()) {
            outageSeen = false;
            answeringSince = null;
            return;
        }

        final boolean answers;
        try {
            answers = probe.answers(target, PROBE_TIMEOUT);
        } catch (final RuntimeException failure) {
            logger.warn("Could not probe {}; holding the players here this pass", live, failure);
            return;
        }

        if (!answers) {
            if (!outageSeen) {
                logger.info(
                        "{} has stopped answering - this is the swap. {} player(s) stay here" + " until it is back",
                        live,
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

    /** Announces the return, counts the last ten seconds and transfers the players at zero. */
    private void announceThenSendHome(final Collection<Player> players) {
        returning = true;
        final List<CountdownPlan.Beat<Announcement>> beats =
                notice.beats(++notices, Homecoming.NOTICE).orElseGet(List::of);
        logger.info(
                "{} is answering again: telling {} player(s) and handing them back to {} in {}s",
                live,
                players.size(),
                home,
                Homecoming.NOTICE.toSeconds());

        for (final CountdownPlan.Beat<Announcement> beat : beats) {
            final var _ = scheduler.after(beat.delay(), () -> {
                final Collection<Player> here = proxy.getAllPlayers();
                voice.say(here, beat.said());
                if (beat.said().kind() != Announcement.Kind.NOW) {
                    return;
                }
                sendHome(here);
                returning = false;
                outageSeen = false;
                answeringSince = null;
            });
        }
    }

    /**
     * Returns whether to transfer the players back.
     *
     * @param outageSeen whether the live proxy refused a connection since the players arrived
     * @param answering how long the live proxy answers without interruption
     */
    static boolean releases(final boolean outageSeen, final Duration answering) {
        if (outageSeen) {
            return answering.compareTo(SETTLE) >= 0;
        }
        return answering.compareTo(RESCUE_AFTER) >= 0;
    }

    private void sendHome(final Collection<Player> players) {
        logger.info("Transferring {} player(s) back to {}", players.size(), home);
        int sent = 0;
        for (final Player player : players) {
            try {
                player.transferToHost(home);
                sent++;
            } catch (final RuntimeException failure) {
                logger.warn("Could not transfer {} back to {}", player.getUsername(), home, failure);
            }
        }
        voice.returned(ProxySwap.OWN_SERVICE, ProxySwap.STANDBY_SERVICE, sent);
    }

    /** Asks whether an address accepts a TCP connection. */
    @FunctionalInterface
    interface Probe {

        /**
         * Returns whether the address answers.
         *
         * @param timeout the time to wait for the handshake
         */
        boolean answers(InetSocketAddress address, Duration timeout);
    }

    /** Resolves the address and connects. A name that does not resolve does not answer. */
    static boolean connects(final InetSocketAddress address, final Duration timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address.getHostString(), address.getPort()), (int) timeout.toMillis());
            return true;
        } catch (final IOException | RuntimeException refused) {
            return false;
        }
    }
}
