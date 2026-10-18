package eu.nordtal.season.proxy.gate;

import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.database.access.AccessDirectory;
import eu.nordtal.season.database.access.AccessState;
import eu.nordtal.season.database.access.LinkCode;
import eu.nordtal.season.proxy.config.GateSpec;
import eu.nordtal.season.settings.network.PlayersSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** Decides each login from {@code AccessDirectory#accessState}. {@code query-timeout-seconds} limits the wait. */
public final class LoginGate {

    private final Logger logger;
    private final ProxyServer proxy;
    private final AccessDirectory access;
    private final FallbackCache fallback;
    private final LoginRoster roster;
    private final GateMessages messages;
    private final GateSpec config;
    private final PlayersSpec players;
    private final Clock clock;

    public LoginGate(
            final Logger logger,
            final ProxyServer proxy,
            final AccessDirectory access,
            final FallbackCache fallback,
            final LoginRoster roster,
            final GateMessages messages,
            final GateSpec config,
            final PlayersSpec players,
            final Clock clock) {
        this.logger = logger;
        this.proxy = proxy;
        this.access = access;
        this.fallback = fallback;
        this.roster = roster;
        this.messages = messages;
        this.config = config;
        this.players = players;
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

        fallback.remember(uuid, state);
        roster.remember(uuid, state);
        if (state.linked()) {
            mirrorMinecraftName(uuid, player.getUsername());
        }

        final int online = proxy.getPlayerCount();
        final int maximum = players.maxPlayers();
        final Instant countdownFrom = state.phase() == SeasonPhase.PRE_LAUNCH ? clock.instant() : null;

        switch (GateOutcome.of(state, online, maximum)) {
            case ALLOW -> {}
            case FULL -> {
                logger.info("Refused {} - the network is full ({} of {})", state.minecraftAccount(), online, maximum);
                event.setResult(ComponentResult.denied(messages.full(state.locale(), online, maximum)));
            }
            case NOT_LINKED -> issueCodeAndDeny(event, player, uuid, state.launch(), countdownFrom);
            case NOT_MEMBER -> event.setResult(ComponentResult.denied(messages.notMember(state.locale())));
            case NO_ACCESS -> event.setResult(ComponentResult.denied(messages.noAccess(state.locale())));
            case PRE_LAUNCH_BUY ->
                event.setResult(
                        ComponentResult.denied(messages.preLaunchBuy(state.locale(), state.launch(), countdownFrom)));
            case PRE_LAUNCH_READY ->
                event.setResult(
                        ComponentResult.denied(messages.preLaunchReady(state.locale(), state.launch(), countdownFrom)));
            case TROUBLE -> event.setResult(ComponentResult.denied(messages.trouble(state.locale())));
        }
    }

    /** Stores the Minecraft name of the login. A failure is ignored. */
    private void mirrorMinecraftName(final UUID uuid, final String username) {
        try {
            access.setMinecraftName(uuid, username);
        } catch (final RuntimeException exception) {
            logger.warn("Could not cache the Minecraft name for {} ({})", uuid, username, exception);
        }
    }

    /** Issues a link code and refuses the login. When the code cannot be issued, it refuses with the trouble screen. */
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
            event.setResult(ComponentResult.denied(messages.trouble(Locale.ENGLISH)));
        }
    }

    /** Decides with {@link GateOutcome#withoutDatabase}. */
    private void fallBackToCache(final LoginEvent event, final UUID uuid) {
        final int online = proxy.getPlayerCount();
        final int maximum = players.maxPlayers();
        final Locale locale = fallback.localeOf(uuid);
        final GateOutcome outcome = GateOutcome.withoutDatabase(fallback.mayJoin(uuid), online, maximum);
        if (outcome == GateOutcome.FULL) {
            event.setResult(ComponentResult.denied(messages.full(locale, online, maximum)));
        } else if (outcome == GateOutcome.TROUBLE) {
            event.setResult(ComponentResult.denied(messages.trouble(locale)));
        }
    }
}
