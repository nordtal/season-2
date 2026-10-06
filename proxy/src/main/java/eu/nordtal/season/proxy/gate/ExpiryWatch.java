package eu.nordtal.season.proxy.gate;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.access.AccessReader;
import eu.nordtal.season.database.access.AccessState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

/**
 * Mid-session expiry: warn a few minutes before access ends, then disconnect when it does.
 *
 * Each player's access end is an event on the scheduler, re-read when it comes; {@link #check} is the pass behind it.
 */
public final class ExpiryWatch {

    /** How long past an instant a look waits, so a clock a little ahead of the database's still finds it passed. */
    static final Duration SLACK = Duration.ofSeconds(1);

    private final Scheduler scheduler;
    private final ProxyServer proxy;
    private final Logger logger;
    private final AccessReader access;
    private final FallbackCache fallback;
    private final GateMessages messages;
    private final Duration warningLead;

    /** Whose warning has already fired for their current approach to expiry. */
    private final Set<UUID> warned = ConcurrentHashMap.newKeySet();

    /** The next look at each connected player's access, set for the instant it matters. */
    private final ConcurrentHashMap<UUID, Scheduler.Task> looks = new ConcurrentHashMap<>();

    private final Clock clock;

    public ExpiryWatch(
            final Scheduler scheduler,
            final ProxyServer proxy,
            final Logger logger,
            final AccessReader access,
            final FallbackCache fallback,
            final GateMessages messages,
            final Duration warningLead,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.scheduler = java.util.Objects.requireNonNull(scheduler, "scheduler");
        this.proxy = proxy;
        this.logger = logger;
        this.access = access;
        this.fallback = fallback;
        this.messages = messages;
        this.warningLead = warningLead;
    }

    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        // Otherwise a player warned once is never warned again on a later session inside the lead time.
        final UUID uuid = event.getPlayer().getUniqueId();
        warned.remove(uuid);
        cancelLook(uuid);
    }

    /** Starts watching a player's access end from the moment they are in. */
    @Subscribe
    public void onPostLogin(final PostLoginEvent event) {
        final Player player = event.getPlayer();
        scheduler.execute(() -> checkOne(player));
    }

    /** One pass over every connected player, for the hub's wake-ups: a change no event announced. */
    public void check() {
        for (final Player player : proxy.getAllPlayers()) {
            checkOne(player);
        }
    }

    private void checkOne(final Player player) {
        final UUID uuid = player.getUniqueId();

        final AccessState state;
        try {
            state = access.accessState(uuid);
        } catch (final RuntimeException exception) {
            logger.warn(
                    "Could not re-check access for {} ({}); the next wake-up of the signal hub asks again",
                    uuid,
                    player.getUsername(),
                    exception);
            return;
        }
        fallback.remember(uuid, state);

        if (!state.mayJoin()) {
            player.disconnect(reasonFor(state));
            warned.remove(uuid);
            cancelLook(uuid);
            return;
        }

        final Instant validUntil = state.validUntil().orElse(null);
        if (validUntil == null) {
            // Normal before SMP for a linked member who never bought anything.
            cancelLook(uuid);
            return;
        }

        final Duration remaining = Duration.between(clock.instant(), validUntil);
        if (remaining.compareTo(warningLead) <= 0) {
            if (warned.add(uuid)) {
                final long minutes = Math.max(1, remaining.toMinutes());
                player.sendMessage(messages.expiryWarning(state.locale(), minutes));
            }
        } else {
            // Renewed after a warning fired: a later approach to the new deadline warns again.
            warned.remove(uuid);
        }
        lookAgainWhenItMatters(player, state);
    }

    /**
     * Sets the next look at this player to the instant the warning is due, then the instant access ends.
     *
     * The look reads access again, so a renewal in between moves it on and a revocation ends it at once.
     */
    private void lookAgainWhenItMatters(final Player player, final AccessState state) {
        final Optional<Duration> delay = untilNextLook(state, clock.instant(), warningLead);
        if (delay.isEmpty()) {
            cancelLook(player.getUniqueId());
            return;
        }
        final Scheduler.Task next = scheduler.after(delay.get(), () -> checkOne(player));
        final Scheduler.Task previous = looks.put(player.getUniqueId(), next);
        if (previous != null) {
            previous.cancel();
        }
    }

    private void cancelLook(final UUID uuid) {
        final Scheduler.Task previous = looks.remove(uuid);
        if (previous != null) {
            previous.cancel();
        }
    }

    /**
     * How long until access next needs a look: the warning's instant while it is ahead, else the end of access.
     *
     * @param state what the database just said
     * @param now the proxy's clock
     * @param lead how long before the end the warning is given
     * @return empty when access has no end, or has already passed, which the hub's next wake-up settles
     */
    static Optional<Duration> untilNextLook(final AccessState state, final Instant now, final Duration lead) {
        final Instant end = state.validUntil().orElse(null);
        if (end == null) {
            return Optional.empty();
        }
        final Duration remaining = Duration.between(now, end);
        if (remaining.compareTo(lead) > 0) {
            return Optional.of(remaining.minus(lead).plus(SLACK));
        }
        if (remaining.isNegative()) {
            return Optional.empty();
        }
        return Optional.of(remaining.plus(SLACK));
    }

    /** Picks the disconnect screen through {@link GateOutcome}, so the sweep and the login gate agree. */
    private Component reasonFor(final AccessState state) {
        return switch (GateOutcome.of(state)) {
            case NOT_LINKED -> messages.unlinked(state.locale());
            case NOT_MEMBER -> messages.notMember(state.locale());
            case NO_ACCESS -> messages.expired(state.locale());
            // A network switched back to PRE_LAUNCH gets the screens the gate would show.
            case PRE_LAUNCH_BUY -> messages.preLaunchBuy(state.locale(), state.launch(), clock.instant());
            case PRE_LAUNCH_READY -> messages.preLaunchReady(state.locale(), state.launch(), clock.instant());
            // Unreachable: only called when mayJoin() is false, and the table never answers FULL or TROUBLE.
            case ALLOW, FULL, TROUBLE -> messages.trouble(state.locale());
        };
    }
}
