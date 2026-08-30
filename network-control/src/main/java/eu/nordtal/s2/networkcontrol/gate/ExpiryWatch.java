package eu.nordtal.s2.networkcontrol.gate;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AccessState;

import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mid-session expiry, per docs/access-stage-c.md: warn a few minutes before access ends, then
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
            // Covers both a natural expiry and a mid-session /revoke-access; the login gate itself
            // is the only thing that tells the two apart in its own messages, and by the time
            // somebody is already connected there is nothing left to distinguish - both mean "you
            // do not have access any more".
            player.disconnect(messages.expired(state.locale()));
            warned.remove(uuid);
            return;
        }

        final Instant validUntil = state.validUntil().orElse(null);
        if (validUntil == null) {
            // mayJoin() being true guarantees this is set; defensive only.
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
}
