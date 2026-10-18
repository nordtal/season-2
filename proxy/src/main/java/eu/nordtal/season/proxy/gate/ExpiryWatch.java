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
 * Warns each player shortly before the period ends and disconnects the player when it ends.
 * {@link #check} checks every player.
 */
public final class ExpiryWatch {

    /** The time that a look waits after the instant it is set for. */
    static final Duration SLACK = Duration.ofSeconds(1);

    private final Scheduler scheduler;
    private final ProxyServer proxy;
    private final Logger logger;
    private final AccessReader access;
    private final FallbackCache fallback;
    private final GateMessages messages;
    private final Duration warningLead;

    /** The players who received the warning. */
    private final Set<UUID> warned = ConcurrentHashMap.newKeySet();

    /** The scheduled look at the access of each player. */
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
        final UUID uuid = event.getPlayer().getUniqueId();
        warned.remove(uuid);
        cancelLook(uuid);
    }

    /** Starts watching the player. */
    @Subscribe
    public void onPostLogin(final PostLoginEvent event) {
        final Player player = event.getPlayer();
        scheduler.execute(() -> checkOne(player));
    }

    /** Checks every connected player. */
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
            warned.remove(uuid);
        }
        lookAgainWhenItMatters(player, state);
    }

    /** Schedules the next look at the player for the time of the warning, then for the end of the period. */
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
     * Returns the time until the next look: until the warning while it is ahead, otherwise until the end of the period.
     *
     * @param lead the time before the end at which the warning is sent
     * @return empty when the period has no end or has ended
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

    /** Returns the disconnect screen for the outcome of {@link GateOutcome}. */
    private Component reasonFor(final AccessState state) {
        return switch (GateOutcome.of(state)) {
            case NOT_LINKED -> messages.unlinked(state.locale());
            case NOT_MEMBER -> messages.notMember(state.locale());
            case NO_ACCESS -> messages.expired(state.locale());
            case PRE_LAUNCH_BUY -> messages.preLaunchBuy(state.locale(), state.launch(), clock.instant());
            case PRE_LAUNCH_READY -> messages.preLaunchReady(state.locale(), state.launch(), clock.instant());
            case ALLOW, FULL, TROUBLE -> messages.trouble(state.locale());
        };
    }
}
