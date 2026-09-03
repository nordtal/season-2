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
 * Mid-session expiry, per docs/access-system.md: warn a few minutes before access ends, then
 * disconnect when it does.
 * <p>
 * <b>Every pass re-checks the database</b> rather than counting down from whatever
 * {@code valid_until} looked like at login. That is slightly more expensive - one query per
 * connected, linked player per {@code expiry-check-interval-seconds} - but it is what makes an
 * admin's {@code /revoke-access} take effect for somebody already on the server, and what makes
 * buying more time mid-session cancel a warning that was about to fire instead of the proxy
 * quietly disconnecting somebody who just paid. The database is the source of truth (see
 * docs/access-system.md); a snapshot taken once at login would not be.
 * </p>
 * <p>
 * A query failure during one pass is <b>not</b> the fallback-cache situation - a player already
 * connected is left alone for that pass and re-checked on the next one; a transient database
 * hiccup must not read as fifty simultaneous expiries. A <em>successful</em> re-check, though, is
 * fed into the same {@link FallbackCache} the login gate uses: without that, a player who has been
 * connected for an hour would only have a login-time-old cache entry, and a database outage
 * starting after login but before their next reconnect would find that entry already past the
 * cache window even though they were, in truth, seen active moments ago.
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
            // mayJoin() is phase-aware since 2026-08-31, so this also catches a player who was let
            // in during PRE_EVENT or START_EVENT and is still connected when the phase moves on -
            // docs/season-phases.md#routing: "a switch to SMP disconnects a player who has no active
            // access". PhaseRouting normally gets there first, on the change itself; this sweep is
            // the safety net for a player whose access simply ran out mid-phase.
            //
            // The MAINTENANCE branch that used to be here is gone with the 2026-08-31 reversal:
            // maintenance no longer refuses a linked member, so !mayJoin() can only mean one of two
            // things now. Either they stopped being a member or unlinked mid-session, or they are in
            // SMP without access. A natural expiry and a mid-session /revoke-access are still one
            // message, because by the time somebody is already connected there is nothing left to
            // distinguish - both mean "you do not have access any more".
            player.disconnect(reasonFor(state));
            warned.remove(uuid);
            return;
        }

        final Instant validUntil = state.validUntil().orElse(null);
        if (validUntil == null) {
            // Nothing to warn about. This is the normal case in PRE_EVENT and START_EVENT, where
            // mayJoin() is true for a linked member who has never bought anything - it stopped
            // being a merely defensive branch when the phase entered the decision.
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
     * It goes through {@link GateOutcome} rather than re-deriving the reason so that the sweep and
     * the login gate can never tell the same player two different stories. Only the {@code NO_ACCESS}
     * wording differs from the gate's, and deliberately: mid-session, "your access has just run out"
     * is the true sentence and "buy a period" is not the whole of it.
     * </p>
     */
    private Component reasonFor(final AccessState state) {
        return switch (GateOutcome.of(state)) {
            case NOT_LINKED -> messages.unlinked(state.locale());
            case NOT_MEMBER -> messages.notMember(state.locale());
            case NO_ACCESS -> messages.expired(state.locale());
            // The network was switched back to PRE_LAUNCH while people were on it - a rehearsal, or
            // an admin undoing an opening. They are shown the same two screens the gate shows,
            // countdown and all, rather than a generic kick: what happened to them is exactly what
            // the gate would now say.
            case PRE_LAUNCH_BUY -> messages.preLaunchBuy(state.locale(), state.launch(), Instant.now());
            case PRE_LAUNCH_READY -> messages.preLaunchReady(state.locale(), state.launch(), Instant.now());
            // Unreachable: this method is only called when mayJoin() was false, and GateOutcome
            // agrees with mayJoin() for every combination (asserted by GateOutcomeTest).
            case ALLOW -> messages.trouble(state.locale());
        };
    }
}
