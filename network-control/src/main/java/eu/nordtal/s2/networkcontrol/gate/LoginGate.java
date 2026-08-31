package eu.nordtal.s2.networkcontrol.gate;

import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;

import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.common.access.LinkCode;
import eu.nordtal.s2.common.access.MemberState;
import eu.nordtal.s2.networkcontrol.config.GateSpec;

import org.slf4j.Logger;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * The season 2 login decision, per docs/access-system.md: one call to
 * {@code AccessDirectory#accessState}, then linked? member and not banned? access active?, each
 * with its own disconnect screen - or, if the database itself could not be reached, the fallback
 * cache instead of any of that.
 * <p>
 * {@code @Subscribe} handlers are asynchronous by default in Velocity 4 (see
 * {@code com.velocitypowered.api.event.Subscribe#async}), so the blocking JDBC call this makes
 * does not run on a Netty I/O thread. How long that call is allowed to block is not this class's
 * concern - it is the connection pool's, configured with a short {@code query-timeout-seconds} in
 * {@code database.yml} so a struggling database fails fast onto the fallback path rather than
 * queueing logins behind it.
 * </p>
 */
public final class LoginGate {

    private final Logger logger;
    private final AccessDirectory access;
    private final FallbackCache fallback;
    private final GateMessages messages;
    private final GateSpec config;

    public LoginGate(final Logger logger, final AccessDirectory access, final FallbackCache fallback,
                     final GateMessages messages, final GateSpec config) {
        this.logger = logger;
        this.access = access;
        this.fallback = fallback;
        this.messages = messages;
        this.config = config;
    }

    @Subscribe
    public void onLogin(final LoginEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();

        final AccessState state;
        try {
            state = access.accessState(uuid);
        } catch (final RuntimeException exception) {
            logger.error("Could not reach the access database for {} ({}); falling back to the "
                    + "last-known-state cache", uuid, player.getUsername(), exception);
            fallBackToCache(event, uuid);
            return;
        }

        // Written on every successful query, healthy path or not - see FallbackCache for why an
        // access-inactive state still has to go through here: it evicts a now-stale positive entry.
        fallback.remember(uuid, state);

        if (!state.linked()) {
            issueCodeAndDeny(event, player, uuid);
            return;
        }
        if (state.memberState() != MemberState.MEMBER) {
            event.setResult(ComponentResult.denied(messages.notMember(state.locale())));
            return;
        }
        if (!state.accessActive()) {
            event.setResult(ComponentResult.denied(messages.noAccess(state.locale())));
            return;
        }
        // Otherwise the event's own default result (ComponentResult.allowed()) stands: route on.
    }

    /**
     * The database answered "unlinked", which is itself a healthy-path result - not the fallback
     * branch. Issuing the code is a second, separate database call, so it can still fail on its
     * own; that failure is treated the same as the database being unreachable in the first place,
     * because there is no code to show either way.
     */
    private void issueCodeAndDeny(final LoginEvent event, final Player player, final UUID uuid) {
        try {
            final LinkCode code = access.issueLinkCode(uuid, Duration.ofMinutes(config.linkCodeTtlMinutes()));
            event.setResult(ComponentResult.denied(messages.notLinked(code.code())));
        } catch (final RuntimeException exception) {
            logger.error("Could not issue a link code for {} ({})", uuid, player.getUsername(), exception);
            // The player's language is unknown either way at this point (that is exactly why the
            // unlinked screen shows both languages), so English is as good a guess as any here.
            event.setResult(ComponentResult.denied(messages.trouble(Locale.ENGLISH)));
        }
    }

    /**
     * Only a player the cache remembers with access that was active when it was cached gets in;
     * everyone else - including every player the cache has simply never heard of - is refused.
     */
    private void fallBackToCache(final LoginEvent event, final UUID uuid) {
        if (fallback.mayJoin(uuid)) {
            return; // default result stands: allowed
        }
        event.setResult(ComponentResult.denied(messages.trouble(fallback.localeOf(uuid))));
    }
}
