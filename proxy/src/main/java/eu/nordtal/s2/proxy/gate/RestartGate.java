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
 * The door, for the seconds between "this proxy is being moved" and "this proxy has stopped".
 * Nobody is turned away at it: an arrival in that window is parked on the standby, exactly like
 * everybody who was already connected when the countdown reached zero.
 *
 * {@code ProxySwap} parks once, at the moment the countdown reaches zero, and what follows is not
 * instant: the worker then waits for the backends to empty, which can take several seconds. Whoever
 * connects inside that window was never parked, because parking already happened, and would
 * otherwise meet the raw Velocity screen when the process stops.
 *
 * Parking rather than refusing is the design, not a fallback: the aim is that connecting through the
 * proxy is impossible for as short a time as possible, and waiting inside the game is fine - the
 * proxy should only be unreachable for its own restart, and before and after that it moves players
 * onto the standbys properly rather than refusing anyone. Refusal is therefore the failure path: it
 * is what an arrival gets when the transfer itself could not be sent - a client older than 1.20.5, a
 * standby that went away between the park and now. The sentence is the same one, which is why
 * {@code gate.restarting} is still here.
 *
 * A player with a seat is never touched: a player {@code ParkedSeats} is holding a seat for is
 * coming home from this very swap, and sending them back to the standby would be a loop. The seat
 * and this door are checked together for exactly that reason.
 *
 * This does nothing on the standby, and on a proxy with no {@code network.yml#public-address}: in
 * both cases {@code ProxySwap} never enters the state, so this never fires. The second is a
 * deployment that drops everybody on every update anyway, and a nicer screen for three of them is
 * not worth a second reader of the update row.
 *
 * The locale comes from {@link FallbackCache}, which is memory and not a round trip. A proxy that
 * is seconds from stopping must not open a database connection to pick a language, and the cache
 * holds everybody who logged in recently - which, in a window that opens at the end of a countdown,
 * is very nearly everybody who is trying.
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
     *                 arriving from the standby and not into a proxy that is going away
     */
    static Handling decide(final boolean stopping, final boolean hasSeat) {
        if (!stopping || hasSeat) {
            return Handling.LET_IN;
        }
        return Handling.PARK;
    }

    /**
     * {@code PostLoginEvent} and not {@code LoginEvent}: a login answers yes or a screen, this with a transfer.
     *
     * By here the player exists, the profile is settled - which is what the locale cache is keyed on - and the
     * connection can carry the transfer packet.
     *
     * Nothing here overrides a decision {@link LoginGate} has already made: that one denies the
     * login itself, so a player refused for a reason of their own never reaches this method.
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
            // The same catch ProxySwap#park has: Velocity refuses the transfer outright for a client older than 1.20.5.
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
     * The decision without the Velocity event around it, so a test can hold it without a {@code Player}.
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
