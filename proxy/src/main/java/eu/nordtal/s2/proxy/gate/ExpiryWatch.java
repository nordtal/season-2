package eu.nordtal.s2.proxy.gate;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.s2.database.access.AccessReader;
import eu.nordtal.s2.database.access.AccessState;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

/**
 * Mid-session expiry: warn a few minutes before access ends, then disconnect when it does.
 *
 * Every pass re-queries; a failed query leaves the player alone until the next pass.
 */
public final class ExpiryWatch {

    private final ProxyServer proxy;
    private final Logger logger;
    private final AccessReader access;
    private final FallbackCache fallback;
    private final GateMessages messages;
    private final Duration warningLead;

    /** Whose warning has already fired for their current approach to expiry. */
    private final Set<UUID> warned = ConcurrentHashMap.newKeySet();

    public ExpiryWatch(
            final ProxyServer proxy,
            final Logger logger,
            final AccessReader access,
            final FallbackCache fallback,
            final GateMessages messages,
            final Duration warningLead) {
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
        warned.remove(event.getPlayer().getUniqueId());
    }

    /** One pass over every connected player, meant for a fixed schedule. */
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
                    "Could not re-check access for {} ({}) during the periodic expiry sweep; "
                            + "trying again next interval",
                    uuid,
                    player.getUsername(),
                    exception);
            return;
        }
        fallback.remember(uuid, state);

        if (!state.mayJoin()) {
            // The safety net for access that ran out mid-phase.
            player.disconnect(reasonFor(state));
            warned.remove(uuid);
            return;
        }

        final Instant validUntil = state.validUntil().orElse(null);
        if (validUntil == null) {
            // Normal before SMP for a linked member who never bought anything.
            return;
        }

        final Duration remaining = Duration.between(Instant.now(), validUntil);
        if (remaining.compareTo(warningLead) <= 0) {
            if (warned.add(uuid)) {
                final long minutes = Math.max(1, remaining.toMinutes());
                player.sendMessage(messages.expiryWarning(state.locale(), minutes));
            }
        } else {
            // Renewed after a warning fired: a later approach to the new deadline warns again.
            warned.remove(uuid);
        }
    }

    /** Picks the disconnect screen through {@link GateOutcome}, so the sweep and the login gate agree. */
    private Component reasonFor(final AccessState state) {
        return switch (GateOutcome.of(state)) {
            case NOT_LINKED -> messages.unlinked(state.locale());
            case NOT_MEMBER -> messages.notMember(state.locale());
            case NO_ACCESS -> messages.expired(state.locale());
            // A network switched back to PRE_LAUNCH gets the screens the gate would show.
            case PRE_LAUNCH_BUY -> messages.preLaunchBuy(state.locale(), state.launch(), Instant.now());
            case PRE_LAUNCH_READY -> messages.preLaunchReady(state.locale(), state.launch(), Instant.now());
            // Unreachable: only called when mayJoin() is false, and GateOutcome agrees for every case.
            case ALLOW -> messages.trouble(state.locale());
        };
    }
}
