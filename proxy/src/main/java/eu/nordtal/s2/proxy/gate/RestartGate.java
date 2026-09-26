package eu.nordtal.s2.proxy.gate;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

/**
 * The door, for the seconds between "this proxy is being moved" and "this proxy has stopped"
 * (season-2-ops/151). Nobody is turned away at it: an arrival in that window is parked on the
 * standby, exactly like everybody who was already connected when the countdown reached zero.
 *
 * <h2>The window this exists for, measured</h2>
 * {@code ProxySwap} parks <b>once</b>, at the moment the countdown reaches zero. What follows is
 * not instant: the worker then waits for the backends to empty, and in run 59 on this host that
 * took sixteen seconds. Whoever connected inside those sixteen seconds was never parked, because
 * parking had already happened, and met the raw Velocity screen when the process went:
 *
 * <pre>
 * 03:20:26  The update moves this proxy: parking 1 player(s) on dev.nordtal.eu:25566 until it is back
 * 03:20:31  [connected player] hmtill has connected
 * 03:20:42  [connected player] hmtill has disconnected: Proxy shutting down.
 * </pre>
 *
 * <h2>Parked rather than refused, which is Till's call - and it was the other way round first</h2>
 * The first build of this class refused, with a sentence, and that was written down here as a
 * decision. Till turned it over on 2026-09-20, in as many words: <i>whoever arrives after the park
 * should be redirected and parked too. The aim of this whole mechanism is that connecting to
 * nordtal through the proxy is impossible for as short a time as possible. Waiting inside the game
 * is fine. So the proxy should really only be unreachable for its own restart - before and after it
 * must move players onto the standbys properly and may refuse nobody.</i>
 *
 * <p>So the refusal is now the <b>failure</b> path and not the design: it is what an arrival gets
 * when the transfer itself could not be sent - a client older than 1.20.5, a standby that went away
 * between the park and now. The sentence is the same one, which is why {@code gate.restarting} is
 * still here.</p>
 *
 * <h2>A player with a seat is never touched</h2>
 * Run 76 on this host, 2026-09-20: the standby handed one player back at 18:46:04, this proxy read
 * their seat at 18:46:09 - and refused them twice, at 18:46:21 and 18:46:37. The seat and the door
 * knew nothing about each other, and the result was an eviction at the end of a choreography whose
 * whole purpose was to have none. A player {@code ParkedSeats} is holding a seat for is coming
 * <em>home</em> from this very swap; sending them back to the standby would be a loop.
 *
 * <h2>Where it does nothing</h2>
 * On the standby, and on a proxy with no {@code network.yml#public-address}: in both cases
 * {@code ProxySwap} never enters the state, so this never fires. The second is a deployment that
 * drops everybody on every update anyway, and a nicer screen for three of them is not worth a
 * second reader of the update row.
 *
 * <p>The locale comes from {@link FallbackCache}, which is memory and not a round trip. A proxy
 * that is seconds from stopping must not open a database connection to pick a language, and the
 * cache holds everybody who logged in recently - which, in a window that opens at the end of a
 * countdown, is very nearly everybody who is trying.</p>
 */
public final class RestartGate {

    private final Logger logger;
    private final BooleanSupplier stopping;
    private final Predicate<UUID> seated;
    private final Predicate<Player> park;
    private final GateMessages messages;
    private final FallbackCache locales;

    /** How many arrivals were sent on to the standby, for the log line and for the test. */
    private final AtomicLong parked = new AtomicLong();

    /** How many could not be, and got the sentence instead. That number should stay at zero. */
    private final AtomicLong refused = new AtomicLong();

    /**
     * @param stopping normally {@code ProxySwap::isStopping} - a supplier rather than the object so
     *                 that the {@code gate} package keeps knowing nothing about {@code update}
     * @param seated   normally {@code ParkedSeats::holds}, and asked before anything else
     * @param park     normally {@code ProxySwap::park}: seat them and hand them the standby's
     *                 address. {@code false} when the transfer could not be sent at all, which is
     *                 the only case that still ends in a screen
     */
    public RestartGate(
            final Logger logger,
            final BooleanSupplier stopping,
            final Predicate<UUID> seated,
            final Predicate<Player> park,
            final GateMessages messages,
            final FallbackCache locales) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.stopping = Objects.requireNonNull(stopping, "stopping");
        this.seated = Objects.requireNonNull(seated, "seated");
        this.park = Objects.requireNonNull(park, "park");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.locales = Objects.requireNonNull(locales, "locales");
    }

    /** What happens to one arrival. */
    enum Handling {

        /** Nothing: no run is moving this proxy, or they are the far end of one. */
        LET_IN,

        /** Straight on to the standby, the same way everybody already connected went. */
        PARK
    }

    /**
     * The rule, without a Velocity event around it.
     *
     * @param stopping whether a run that moves this proxy has reached zero
     * @param hasSeat  whether {@code ParkedSeats} is holding a seat for them, which means they are
     *                 arriving <em>from</em> the standby and not into a proxy that is going away
     */
    static Handling decide(final boolean stopping, final boolean hasSeat) {
        if (!stopping || hasSeat) {
            return Handling.LET_IN;
        }
        return Handling.PARK;
    }

    /**
     * {@code PostLoginEvent} and not {@code LoginEvent}, and the difference is the whole change: a
     * login can only be answered with a yes or a screen, and this one has to answer with a
     * <em>transfer</em>. By here the player exists, the profile is settled - which is what the
     * locale cache is keyed on - and the connection can carry the transfer packet.
     *
     * <p>Nothing here overrides a decision {@link LoginGate} has already made: that one denies the
     * login itself, so a player refused for a reason of their own never reaches this method.</p>
     */
    @Subscribe
    public void onPostLogin(final PostLoginEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        if (decide(stopping.getAsBoolean(), seated.test(uuid)) == Handling.LET_IN) {
            return;
        }
        boolean moved;
        try {
            moved = park.test(player);
        } catch (final RuntimeException failure) {
            // The same catch ProxySwap#park has, and for the same reason: Velocity refuses the
            // transfer outright for a client older than 1.20.5, with an exception rather than a
            // returned failure.
            logger.warn("Could not park {} on arrival", player.getUsername(), failure);
            moved = false;
        }
        if (moved) {
            logger.info(
                    "Parked {} ({}) on arrival: this proxy is being moved by an update, so"
                            + " they went straight on to the standby ({} so far)",
                    player.getUsername(),
                    uuid,
                    parked.incrementAndGet());
            return;
        }
        player.disconnect(refuse(uuid, player.getUsername()));
    }

    /**
     * The decision without the Velocity event around it, so a test can hold it without a
     * {@code Player}.
     *
     * @return the screen they get
     */
    Component refuse(final UUID mcUuid, final String username) {
        final Locale locale = locales.localeOf(mcUuid);
        logger.warn(
                "Could not park {} ({}) and this proxy stops in a moment, so they were sent"
                        + " away with a sentence instead of a dropped connection ({} so far)",
                username,
                mcUuid,
                refused.incrementAndGet());
        return messages.restarting(locale);
    }

    /** @return how many arrivals were sent on to the standby since the proxy started */
    public long parkedCount() {
        return parked.get();
    }

    /**
     * @return how many arrivals got the screen because the transfer could not be sent. A run that
     *         went the way it is meant to leaves this at zero
     */
    public long refusedCount() {
        return refused.get();
    }
}
