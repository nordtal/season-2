package eu.nordtal.season.proxy.gate;

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
 * Parks an arrival on the standby between a proxy swap's countdown reaching zero and the proxy stopping.
 *
 * Refusal is only the failure path, for a transfer that could not be sent; a player with a seat is let in.
 */
public final class RestartGate {

    private final Logger logger;
    private final BooleanSupplier stopping;
    private final Predicate<UUID> seated;
    private final Predicate<Player> park;
    private final GateMessages messages;
    private final FallbackCache locales;

    /** How many arrivals were sent on to the standby. */
    private final AtomicLong parked = new AtomicLong();

    /** How many could not be and got the screen instead; it should stay at zero. */
    private final AtomicLong refused = new AtomicLong();

    /**
     * Takes suppliers rather than the swap itself, so the {@code gate} package knows nothing about {@code update}.
     *
     * @param park seats the player and transfers them; {@code false} when the transfer could not be sent
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

        /** No run is moving this proxy, or they are the far end of one. */
        LET_IN,

        /** Straight on to the standby, the same way everybody already connected went. */
        PARK
    }

    /**
     * The rule, without a Velocity event around it.
     *
     * @param hasSeat whether {@code ParkedSeats} holds a seat for them, so they are arriving from the standby
     */
    static Handling decide(final boolean stopping, final boolean hasSeat) {
        if (!stopping || hasSeat) {
            return Handling.LET_IN;
        }
        return Handling.PARK;
    }

    /** {@code PostLoginEvent}, not {@code LoginEvent}: the connection can carry the transfer packet by here. */
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
            // Velocity refuses the transfer outright for a client older than 1.20.5.
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
     * The refusal without the Velocity event around it.
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

    /** How many arrivals were sent on to the standby since the proxy started. */
    public long parkedCount() {
        return parked.get();
    }

    /** Returns how many arrivals got the screen because the transfer could not be sent; zero on a clean run. */
    public long refusedCount() {
        return refused.get();
    }
}
