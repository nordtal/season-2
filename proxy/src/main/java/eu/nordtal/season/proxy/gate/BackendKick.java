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
 * What happens to a player a backend has just kicked.
 *
 * A reason proves a decision and is shown as a disconnect; no reason means a dead connection and goes to limbo.
 */
public final class BackendKick {

    enum Decision {
        LEAVE,
        /** Disconnect with the backend's own component instead of Velocity's English wrapper. */
        SHOW_REASON,
        /** No reason was given: redirect to the waiting room instead of disconnecting. */
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
                // A Notify or a RedirectPlayer is somebody else's decision.
            }
        }
    }

    /**
     * The whole decision, testable without a running proxy.
     *
     * @param result what Velocity was going to do
     * @param reason the backend's own kick component, or {@code null} if it sent none
     */
    static Decision decide(final KickedFromServerEvent.ServerKickResult result, final @Nullable Component reason) {
        if (!(result instanceof KickedFromServerEvent.DisconnectPlayer)) {
            return Decision.LEAVE;
        }
        return reason == null ? Decision.TO_LIMBO : Decision.SHOW_REASON;
    }

    /** Suspends the lost backend and redirects to the waiting room, unless there is nowhere to send them. */
    private void toLimbo(final KickedFromServerEvent event) {
        final String from = event.getServer().getServerInfo().getName();
        // Either waiting room, or a reasonless kick during a swap bounces the player right back into it.
        if (servers.isWaitingRoom(from)) {
            logger.warn(
                    "'{}' lost {} with no reason given, and it is itself a waiting room; "
                            + "leaving Velocity's own result in place",
                    from,
                    event.getPlayer().getUsername());
            return;
        }

        // The live room first, the standby as a fallback, the same order PhaseRouting uses.
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

        // PackStation reads this through LimboHold's destinationAvailable.
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
