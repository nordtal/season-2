package eu.nordtal.s2.networkcontrol.gate;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AccessState;

import net.kyori.adventure.text.Component;

import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mid-session expiry: warn a few minutes before access ends, then disconnect when it does.
 * <p>
 * <b>Every pass re-checks the database</b> rather than counting down from the {@code valid_until}
 * seen at login, so a mid-session revoke takes effect and buying more time cancels a warning that
 * was about to fire.
 * </p>
 * <p>
 * A query failure during one pass is <b>not</b> the fallback-cache situation: the player is left
 * alone and re-checked next pass, because a transient hiccup must not read as fifty simultaneous
 * expiries. A successful re-check is fed into the same {@link FallbackCache} the login gate uses,
 * so a long-connected player's entry is not already past the cache window by their next reconnect.
 * </p>
 */
public final class ExpiryWatch {

    private final ProxyServer proxy;
    private final Logger logger;
    private final AccessDirectory access;
    private final FallbackCache fallback;
    private final GateMessages messages;
    private final Duration warningLead;

    /** Whose warning has already fired for their current approach to expiry. Cleared on disconnect
     * and whenever a re-check finds them no longer within the warning window - see {@link #check()}. */
    private final Set<UUID> warned = ConcurrentHashMap.newKeySet();

    public ExpiryWatch(final ProxyServer proxy, final Logger logger, final AccessDirectory access,
                       final FallbackCache fallback, final GateMessages messages, final Duration warningLead) {
        this.proxy = proxy;
        this.logger = logger;
        this.access = access;
        this.fallback = fallback;
        this.messages = messages;
        this.warningLead = warningLead;
    }

    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        // Without this, a player warned once and then disconnecting normally would never be
        // warned again on a later session that happens to land inside the same lead time.
        warned.remove(event.getPlayer().getUniqueId());
    }

    /** One pass over every connected player. Meant to be called on a fixed schedule. */
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
            logger.warn("Could not re-check access for {} ({}) during the periodic expiry sweep; "
                    + "trying again next interval", uuid, player.getUsername(), exception);
            return;
        }
        fallback.remember(uuid, state);

        if (!state.mayJoin()) {
            // mayJoin() is phase-aware, so this also catches a player let in during PRE_EVENT or
            // START_EVENT who is still connected when the phase moves to SMP. PhaseRouting normally
            // gets there first; this sweep is the safety net for access that ran out mid-phase.
            player.disconnect(reasonFor(state));
            warned.remove(uuid);
            return;
        }

        final Instant validUntil = state.validUntil().orElse(null);
        if (validUntil == null) {
            // Nothing to warn about: the normal case in PRE_EVENT and START_EVENT, where mayJoin()
            // is true for a linked member who has never bought anything.
            return;
        }

        final Duration remaining = Duration.between(Instant.now(), validUntil);
        if (remaining.compareTo(warningLead) <= 0) {
            if (warned.add(uuid)) {
                final long minutes = Math.max(1, remaining.toMinutes());
                player.sendMessage(messages.expiryWarning(state.locale(), minutes));
            }
        } else {
            // Access was renewed after a warning already fired: let a later approach to the new,
            // pushed-back deadline warn again instead of staying silently "already warned".
            warned.remove(uuid);
        }
    }

    /**
     * Picks the screen for a player this sweep has just decided may no longer be here.
     * <p>
     * Goes through {@link GateOutcome} rather than re-deriving the reason, so the sweep and the
     * login gate cannot tell the same player two different stories. Only the {@code NO_ACCESS}
     * wording differs, deliberately.
     * </p>
     */
    private Component reasonFor(final AccessState state) {
        return switch (GateOutcome.of(state)) {
            case NOT_LINKED -> messages.unlinked(state.locale());
            case NOT_MEMBER -> messages.notMember(state.locale());
            case NO_ACCESS -> messages.expired(state.locale());
            // The network was switched back to PRE_LAUNCH while people were on it: they get the
            // same screens the gate would now show them, rather than a generic kick.
            case PRE_LAUNCH_BUY -> messages.preLaunchBuy(state.locale(), state.launch(), Instant.now());
            case PRE_LAUNCH_READY -> messages.preLaunchReady(state.locale(), state.launch(), Instant.now());
            // Unreachable: this method is only called when mayJoin() was false, and GateOutcome
            // agrees with mayJoin() for every combination (asserted by GateOutcomeTest).
            case ALLOW -> messages.trouble(state.locale());
        };
    }
}
