package eu.nordtal.season.proxy.gate;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import eu.nordtal.season.proxy.PhaseServers;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Handles a player whom a backend kicked. A kick with a reason disconnects the player. A kick without a reason moves
 * the player to limbo.
 */
public final class BackendKick {

    enum Decision {
        LEAVE,
        /** Disconnect the player with the reason of the backend. */
        SHOW_REASON,
        /** Move the player to the waiting room. */
        TO_LIMBO
    }

    private final ProxyServer proxy;
    private final PhaseServers servers;
    private final BackendHealth health;
    private final GateMessages messages;
    private final LoginRoster roster;
    private final Logger logger;

    public BackendKick(
            final ProxyServer proxy,
            final PhaseServers servers,
            final BackendHealth health,
            final GateMessages messages,
            final LoginRoster roster,
            final Logger logger) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.health = Objects.requireNonNull(health, "health");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Subscribe
    public void onKickedFromServer(final KickedFromServerEvent event) {
        final @Nullable Component reason = event.getServerKickReason().orElse(null);
        switch (decide(event.getResult(), reason)) {
            case SHOW_REASON ->
                event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
                        Objects.requireNonNull(reason, "SHOW_REASON only happens when a reason was given")));
            case TO_LIMBO -> toLimbo(event);
            case LEAVE -> {
            }
        }
    }

    /**
     * Returns the decision.
     *
     * @param reason the reason of the backend, {@code null} without one
     */
    static Decision decide(final KickedFromServerEvent.ServerKickResult result, final @Nullable Component reason) {
        if (!(result instanceof KickedFromServerEvent.DisconnectPlayer)) {
            return Decision.LEAVE;
        }
        return reason == null ? Decision.TO_LIMBO : Decision.SHOW_REASON;
    }

    /** Suspends the backend and moves the player to the waiting room. */
    private void toLimbo(final KickedFromServerEvent event) {
        final String from = event.getServer().getServerInfo().getName();
        if (servers.isWaitingRoom(from)) {
            logger.warn(
                    "'{}' lost {} with no reason given, and it is itself a waiting room; "
                            + "leaving Velocity's own result in place",
                    from,
                    event.getPlayer().getUsername());
            return;
        }

        final RegisteredServer target = proxy.getServer(servers.limbo())
                .or(() -> proxy.getServer(servers.limboStandby()))
                .orElse(null);
        if (target == null) {
            logger.error(
                    "{} lost its connection to '{}' with no reason given, and neither '{}' nor "
                            + "'{}' is registered on this proxy to hold them in instead",
                    event.getPlayer().getUsername(),
                    from,
                    servers.limbo(),
                    servers.limboStandby());
            return;
        }

        health.suspend(from);
        logger.warn(
                "{} lost its connection to '{}' with no reason given; moving them to '{}' "
                        + "instead of a disconnect screen, and holding '{}' suspended for {}s",
                event.getPlayer().getUsername(),
                from,
                target.getServerInfo().getName(),
                from,
                BackendHealth.RETRY.toSeconds());
        final Locale locale = roster.localeOf(event.getPlayer().getUniqueId());
        event.setResult(KickedFromServerEvent.RedirectPlayer.create(target, messages.connectionLost(locale)));
    }
}
