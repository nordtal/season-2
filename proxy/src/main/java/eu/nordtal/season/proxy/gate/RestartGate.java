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
 * Transfers each player who joins between the end of the proxy swap countdown and the stop of the proxy to the standby.
 * The gate refuses a player when the transfer fails.
 */
public final class RestartGate {

    private final Logger logger;
    private final BooleanSupplier stopping;
    private final Predicate<UUID> seated;
    private final Predicate<Player> park;
    private final GateMessages messages;
    private final FallbackCache locales;

    /** The number of transferred players. */
    private final AtomicLong parked = new AtomicLong();

    /** The number of refused players. */
    private final AtomicLong refused = new AtomicLong();

    /** @param park seats the player and transfers the player, returns {@code false} when the transfer fails */
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

    /** The handling of a joining player. */
    enum Handling {

        /** The player joins this proxy. */
        LET_IN,

        /** The player is transferred to the standby. */
        PARK
    }

    /**
     * Returns the handling.
     *
     * @param hasSeat whether {@code ParkedSeats} holds a seat for the player
     */
    static Handling decide(final boolean stopping, final boolean hasSeat) {
        if (!stopping || hasSeat) {
            return Handling.LET_IN;
        }
        return Handling.PARK;
    }

    /** Transfers the player to the standby while the proxy stops. */
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

    /** Returns the screen of the refused player. */
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

    /** Returns the number of transferred players. */
    public long parkedCount() {
        return parked.get();
    }

    /** Returns the number of refused players. */
    public long refusedCount() {
        return refused.get();
    }
}
