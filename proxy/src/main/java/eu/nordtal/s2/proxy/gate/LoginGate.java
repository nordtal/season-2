package eu.nordtal.s2.proxy.gate;

import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.database.access.AccessDirectory;
import eu.nordtal.s2.database.access.AccessState;
import eu.nordtal.s2.database.access.LinkCode;
import eu.nordtal.s2.proxy.config.GateSpec;
import eu.nordtal.s2.proxy.config.NetworkSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The season 2 login decision, from one {@code AccessDirectory#accessState} round trip.
 *
 * Velocity 4 runs {@code @Subscribe} handlers off the Netty threads; {@code query-timeout-seconds} bounds the wait.
 */
public final class LoginGate {

    private final Logger logger;
    private final ProxyServer proxy;
    private final AccessDirectory access;
    private final FallbackCache fallback;
    private final LoginRoster roster;
    private final GateMessages messages;
    private final GateSpec config;
    private final NetworkSpec network;
    private final Clock clock;

    public LoginGate(
            final Logger logger,
            final ProxyServer proxy,
            final AccessDirectory access,
            final FallbackCache fallback,
            final LoginRoster roster,
            final GateMessages messages,
            final GateSpec config,
            final NetworkSpec network,
            final Clock clock) {
        this.logger = logger;
        this.proxy = proxy;
        this.access = access;
        this.fallback = fallback;
        this.roster = roster;
        this.messages = messages;
        this.config = config;
        this.network = network;
        this.clock = clock;
    }

    @Subscribe
    public void onLogin(final LoginEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();

        final AccessState state;
        try {
            state = access.accessState(uuid);
        } catch (final RuntimeException exception) {
            logger.error(
                    "Could not reach the access database for {} ({}); falling back to the " + "last-known-state cache",
                    uuid,
                    player.getUsername(),
                    exception);
            fallBackToCache(event, uuid);
            return;
        }

        // Written on every successful query, so an inactive state evicts a stale positive entry.
        fallback.remember(uuid, state);
        // The facts the /phase command and the play-time writer need, from the same row.
        roster.remember(uuid, state);
        // The one place this proxy sees a player's Minecraft name; account_link caches it.
        if (state.linked()) {
            mirrorMinecraftName(uuid, player.getUsername());
        }

        final Instant countdownFrom = state.phase() == SeasonPhase.PRE_LAUNCH ? clock.instant() : null;

        switch (GateOutcome.of(state)) {
            // Refused only when full; where they land is PlayerRouter's question.
            case ALLOW -> refuseIfFull(event, state);
            case NOT_LINKED -> issueCodeAndDeny(event, player, uuid, state.launch(), countdownFrom);
            case NOT_MEMBER -> event.setResult(ComponentResult.denied(messages.notMember(state.locale())));
            case NO_ACCESS -> event.setResult(ComponentResult.denied(messages.noAccess(state.locale())));
            case PRE_LAUNCH_BUY ->
                event.setResult(
                        ComponentResult.denied(messages.preLaunchBuy(state.locale(), state.launch(), countdownFrom)));
            case PRE_LAUNCH_READY ->
                event.setResult(
                        ComponentResult.denied(messages.preLaunchReady(state.locale(), state.launch(), countdownFrom)));
        }
    }

    /** Caches the Minecraft name this login just presented, best-effort. */
    private void mirrorMinecraftName(final UUID uuid, final String username) {
        try {
            access.setMinecraftName(uuid, username);
        } catch (final RuntimeException exception) {
            logger.warn("Could not cache the Minecraft name for {} ({})", uuid, username, exception);
        }
    }

    /**
     * The network-wide player limit, and the only place it is enforced.
     *
     * Checked after the access decision, so an admin can still enter a full network to fix it.
     */
    private void refuseIfFull(final LoginEvent event, final AccessState state) {
        final int maximum = network.maxPlayers();
        final int online = proxy.getPlayerCount();
        if (online < maximum || state.admin()) {
            return;
        }
        logger.info("Refused {} - the network is full ({} of {})", state.minecraftAccount(), online, maximum);
        event.setResult(ComponentResult.denied(messages.full(state.locale(), online, maximum)));
    }

    /** Issues a link code and refuses; a failure to issue one is treated as an unreachable database. */
    private void issueCodeAndDeny(
            final LoginEvent event,
            final Player player,
            final UUID uuid,
            final @Nullable Instant launch,
            final @Nullable Instant now) {
        try {
            final LinkCode code = access.issueLinkCode(uuid, Duration.ofMinutes(config.linkCodeTtlMinutes()));
            event.setResult(ComponentResult.denied(messages.notLinked(code.code(), launch, now)));
        } catch (final RuntimeException exception) {
            logger.error("Could not issue a link code for {} ({})", uuid, player.getUsername(), exception);
            // The unlinked screen is bilingual because the language is unknown; English is a guess.
            event.setResult(ComponentResult.denied(messages.trouble(Locale.ENGLISH)));
        }
    }

    /** Lets in only a player the cache remembers as allowed, under the last known phase. */
    private void fallBackToCache(final LoginEvent event, final UUID uuid) {
        if (fallback.mayJoin(uuid)) {
            // The limit still applies to everyone, since the admin flag is what could not be read.
            final int maximum = network.maxPlayers();
            final int online = proxy.getPlayerCount();
            if (online >= maximum) {
                event.setResult(ComponentResult.denied(messages.full(fallback.localeOf(uuid), online, maximum)));
            }
            return; // default result stands: allowed
        }
        event.setResult(ComponentResult.denied(messages.trouble(fallback.localeOf(uuid))));
    }
}
