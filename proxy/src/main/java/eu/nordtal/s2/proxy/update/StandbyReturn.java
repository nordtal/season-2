package eu.nordtal.s2.proxy.update;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.s2.proxy.routing.ProxyRole;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * The standby proxy's half of a swap: it holds the network, and it sends it home by itself
 * (season-2-ops/121).
 *
 * <h2>By itself, and that is the requirement</h2>
 * The obvious design is for {@code steward-worker} to tell the standby when to release - it is
 * driving the run, it knows when the live proxy is healthy, and it already talks to every service.
 * It is also, in the one case this has to survive, <b>the process that has failed</b>: a run that
 * dies with players parked here dies holding the only instruction that would ever have moved them.
 * So nothing here waits to be told (Till, 2026-09-19). It watches the public address and decides.
 *
 * <h2>Why it will not send anybody back too early, which is the way this breaks</h2>
 * The live proxy parks the network at the instant the counter reaches zero, and is then stopped a
 * few seconds later - steward-worker waits until the backends are empty first. For those seconds
 * <em>both</em> proxies are up and the public address answers perfectly well. A standby that simply
 * asked "is it up" would hand everybody straight back into a proxy that is seconds from stopping -
 * and the live proxy parks once per run, so the second time it would not catch them. Everyone would
 * be disconnected by the mechanism built to stop exactly that.
 *
 * <p>So the release needs an <b>outage</b> and not an address: nobody goes home until this process
 * has seen the public address refuse a connection at least once. That is the only evidence that the
 * proxy players are being sent back to is a new one.</p>
 *
 * <h2>And why it will not hold anybody for ever, which is the other way</h2>
 * A run cancelled after the park and before the stop leaves players here with an outage that never
 * happens. {@link #RESCUE_AFTER} is the answer: if the public address has answered continuously for
 * that long and this proxy is still holding people, the swap is not coming and they are sent home
 * anyway. A minute of a loading screen too many is the cost; the alternative is a waiting room
 * nothing ever opens.
 *
 * <h2>The probe is a TCP connect and nothing more</h2>
 * Not a ping, not a login. What it has to answer is "is there a proxy accepting connections on that
 * port", and a completed TCP handshake is exactly that - measured against this host's own
 * {@code velocity-4.2.0-30.jar}, 2026-09-19: {@code VelocityServer} fires
 * {@code ProxyInitializeEvent} <em>before</em> it binds the listener, so a port that answers is a
 * proxy whose plugins are already up. A socket that opens before the plugin is ready would be the
 * one failure a readiness check must not have.
 */
public final class StandbyReturn {

    /** How often this runs. Two seconds, because a person is looking at a loading screen. */
    public static final Duration INTERVAL = Duration.ofSeconds(2);

    /** How long to wait for the handshake. Short: it is a port on the same machine. */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(1);

    /**
     * How long the public address must have been answering before players are sent home without an
     * outage having been seen - the self-rescue for a run that was cancelled between the park and
     * the stop.
     */
    static final Duration RESCUE_AFTER = Duration.ofMinutes(1);

    /**
     * How long it must have been answering <em>after</em> an outage. Longer than one tick on
     * purpose, so a release needs two passes: the moment a listener binds is not the moment the
     * process behind it has finished starting, and the cost of being early is a failed login for
     * everybody at once.
     */
    static final Duration SETTLE = Duration.ofSeconds(4);

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final SwapStore seats;
    private final ProxyRole role;
    private final InetSocketAddress home;
    private final Clock clock;
    private final Probe probe;

    /**
     * The voice of the return (season-2-ops/118). Everything this network says is said on the way
     * out; the way back was silent, and this one lands in the middle of a game rather than at the
     * end of a wait - the player has been playing on the SMP through the standby the whole time.
     */
    private final Homecoming voice;

    /** The same planner the way out uses, so the return counts in the same voice. */
    private final Countdown notice = new Countdown();

    /** A new number per return, because {@link Countdown} plans one row once. */
    private long notices;

    /**
     * Whether the network has been promised a return and is being counted towards one.
     *
     * <p>Once this is set the decision above is not asked again: the transfer is a scheduled beat
     * now, and a probe that flickers during those ten seconds must not start a second countdown or
     * take the promise back. If the live proxy really has gone again, the transfer fails, nobody
     * moves, and the next pass sees the outage from the beginning.</p>
     */
    private boolean returning;

    /** Whether the public address has refused a connection since this proxy last had players. */
    private boolean outageSeen;

    /** When it started answering again, or {@code null} while it is not. */
    private Instant answeringSince;

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
     * @param probe how to ask whether an address answers. A seam and not a setting: the decision
     *              below is the part worth asserting, and asserting it against a real socket would
     *              mean binding a port in a unit test
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

    /** @return whether this process will do anything at all - for the startup log line */
    public boolean isArmed() {
        return home != null && role.isStandby();
    }

    /**
     * One pass: say how many players are here, and send them home if it is time.
     *
     * <p>Never throws, for the reason every other watch in this package gives: a task that throws
     * is a task Velocity stops running, and here that would strand the whole network in a waiting
     * room with nothing left to open it.</p>
     */
    public void check() {
        if (!isArmed()) {
            return;
        }
        final Collection<Player> players = proxy.getAllPlayers();
        final Instant now = clock.instant();

        try {
            // Always, including the zero. The worker is waiting for that zero to stop this
            // container, and "no row" has to keep meaning "this standby has never spoken".
            seats.reportStandby(players.size(), now);
        } catch (final RuntimeException failure) {
            logger.warn("Could not report how many players the standby is holding", failure);
        }

        if (returning) {
            // Counted down to and scheduled. Nothing this pass observes can improve on a promise
            // already made to somebody watching a number.
            return;
        }
        if (players.isEmpty()) {
            // Nobody to protect, so nothing observed about the live proxy is worth keeping: the
            // next group to arrive has to see its own outage.
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

    /**
     * Tells the players the network is back, counts the last ten seconds out loud, and transfers
     * them on zero (season-2-ops/118).
     *
     * <p>Ten seconds is the promise, not an optimisation: the return interrupts whatever the player
     * is doing exactly as much as the way out did, and that is the reason the way out has a
     * countdown. Somebody sitting in the standby's waiting room gets the chat half only -
     * {@link Homecoming} draws that line.</p>
     *
     * <p>Scheduled rather than slept through: this runs on the proxy's own scheduler thread, and a
     * pass that blocks for ten seconds is ten seconds in which nothing else on this proxy ticks.</p>
     */
    private void announceThenSendHome(final Collection<Player> players) {
        returning = true;
        final List<Countdown.Beat> beats =
                notice.beats(++notices, Homecoming.NOTICE).orElseGet(List::of);
        logger.info(
                "{} is answering again: telling {} player(s) and handing them back in {}s",
                home,
                players.size(),
                Homecoming.NOTICE.toSeconds());

        for (final Countdown.Beat beat : beats) {
            proxy.getScheduler()
                    .buildTask(plugin, () -> {
                        // Asked again per beat rather than held: somebody who logged out during the
                        // countdown is not a player any more, and somebody who logged in is owed the same
                        // sentence as everybody else.
                        final Collection<Player> here = proxy.getAllPlayers();
                        voice.say(here, beat.announcement());
                        if (beat.announcement().kind() != Announcement.Kind.NOW) {
                            return;
                        }
                        // The sentence first and the transfer second, which is the opposite of the way
                        // out. There the move is what a player can be hurt by; here the move is what ends
                        // their connection to this proxy, and a message sent after it reaches nobody.
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
     * The decision, without a socket and without Velocity - the split {@code BackendKick#decide} and
     * {@code Evacuation#roomFor} both have, for the reason they both give.
     *
     * @param outageSeen whether the public address has refused a connection since these players
     *                   arrived
     * @param answering  how long it has been answering without interruption
     * @return whether to send the players home now
     */
    static boolean releases(final boolean outageSeen, final Duration answering) {
        if (outageSeen) {
            return answering.compareTo(SETTLE) >= 0;
        }
        // No outage, so this is not a swap that happened - it is one that was called off, or
        // somebody who joined this port by hand. Either way they do not belong here.
        return answering.compareTo(RESCUE_AFTER) >= 0;
    }

    private void sendHome(final Collection<Player> players) {
        logger.info("Transferring {} player(s) back to {}", players.size(), home);
        for (final Player player : players) {
            try {
                player.transferToHost(home);
            } catch (final RuntimeException failure) {
                // One client that cannot be transferred must not cost the rest theirs. They stay
                // here; the next pass tries again, because nothing has cleared their state.
                logger.warn("Could not transfer {} back to {}", player.getUsername(), home, failure);
            }
        }
    }

    /** Whether an address accepts a TCP connection. */
    @FunctionalInterface
    interface Probe {

        /**
         * @param address where to knock
         * @param timeout how long to wait for the handshake
         * @return whether it answered
         */
        boolean answers(InetSocketAddress address, Duration timeout);
    }

    /**
     * The real probe.
     *
     * <p>The address held here is deliberately unresolved - see {@code SwapAddresses} - so this is
     * also where the name is looked up, once per pass, off the netty threads. A name that does not
     * resolve reads as "not answering", which is the same thing as far as a player parked here is
     * concerned.</p>
     */
    static boolean connects(final InetSocketAddress address, final Duration timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address.getHostString(), address.getPort()), (int) timeout.toMillis());
            return true;
        } catch (final IOException | RuntimeException refused) {
            return false;
        }
    }
}
