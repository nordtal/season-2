package eu.nordtal.s2.proxy.gate;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import eu.nordtal.s2.proxy.PhaseServers;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * What happens to a player a backend has just kicked.
 *
 * Shown their own reason if it gave one, moved to the waiting room if it did not.
 *
 * {@link KickedFromServerEvent#getServerKickReason()} is only ever present when the backend itself
 * sent an explicit {@code Disconnect} packet - a ban, an admin's {@code /kick}, a whitelist refusal,
 * or a backend's own graceful self-kick such as {@code smp.error.database-unreachable}. A
 * connection that instead dies - the container stops, it crashes, Velocity loses the socket -
 * reaches this event through a completely different path with the reason argument passed as a
 * literal {@code null}, precisely because nothing on that path ever decided anything to tell the
 * player. A present reason is therefore not merely usually a decision; it is Velocity's own proof
 * that one was made, on the one path capable of producing it. See {@link #decide} for the branch
 * this becomes.
 *
 * A reason was given: {@link Decision#SHOW_REASON}. Velocity wraps a backend's kick reason in one
 * of its own sentences before it puts it on the screen, in English, from its own translation
 * bundle rather than ours - so the one screen a backend writes carefully, the whole point of which
 * is to tell a player their progress is safe, would arrive underneath a line naming an internal
 * server name in the wrong language. This branch replaces the wrapper and disconnects with the
 * backend's own component instead: a kick somebody deliberately caused stays a disconnect, on the
 * belief that a player who was banned or refused entry deserves to be told so plainly, not parked
 * wordlessly in a room with no explanation.
 *
 * No reason at all: {@link Decision#TO_LIMBO}. What Velocity's own result actually is on this path
 * is not something this repository can observe, and in practice it lands a player on a disconnect
 * screen: the network loses them even though the proxy is still standing. So they are redirected
 * into {@code limbo} instead, and {@link BackendHealth} suspends the backend that lost them so the
 * ordinary waiting-room sweep, not a second kick, is what decides when they go back - which is what
 * keeps a backend that immediately kicks again from bouncing them in a loop.
 *
 * Not a disconnect at all: {@link Decision#LEAVE}. A {@code Notify} (the player is on another
 * server and stays there) and a {@code RedirectPlayer} (something else has already chosen where
 * they go) are left exactly as they are; touching either would move a player this class was never
 * asked to move.
 */
public final class BackendKick {

    /** The whole decision, before a single Velocity call is made. */
    enum Decision {
        /** Leave Velocity's own result exactly as it is. */
        LEAVE,
        /** Disconnect, but with the backend's own component instead of Velocity's wrapper. */
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

    /**
     * @param proxy  used only to look up the waiting room by name when a redirect is needed
     * @param servers the two waiting-room names, the same ones routing uses - and
     *               {@code PackStation} use
     * @param health   suspended for exactly the backend named in the event, never any other one -
     *                 see {@link BackendHealth}
     * @param messages where the redirect's own line comes from, so that it is the player's language
     *                 and not this file's
     * @param roster   asked for that player's locale, the same way {@code PlayerRouter} does
     * @param logger   the plugin logger
     */
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

    /**
     * @param event Velocity's kick event, already carrying the result it was going to use
     */
    @Subscribe
    public void onKickedFromServer(final KickedFromServerEvent event) {
        final @Nullable Component reason = event.getServerKickReason().orElse(null);
        switch (decide(event.getResult(), reason)) {
            case SHOW_REASON ->
                event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
                        Objects.requireNonNull(reason, "SHOW_REASON only happens when a reason was given")));
            case TO_LIMBO -> toLimbo(event);
            case LEAVE -> {
                // Nothing: a Notify or a RedirectPlayer is somebody else's decision.
            }
        }
    }

    /**
     * The whole decision, as a function of the two things it depends on.
     *
     * Static and separate because a {@link KickedFromServerEvent} cannot be built without a
     * {@code Player} and a {@code RegisteredServer}, which exist only on a running proxy - so the
     * handler above can be exercised nowhere and this can be exercised everywhere.
     *
     * @param result what Velocity was going to do
     * @param reason the backend's own kick component, or {@code null} if it sent none
     * @return what to do instead
     */
    static Decision decide(final KickedFromServerEvent.ServerKickResult result, final @Nullable Component reason) {
        if (!(result instanceof KickedFromServerEvent.DisconnectPlayer)) {
            return Decision.LEAVE;
        }
        return reason == null ? Decision.TO_LIMBO : Decision.SHOW_REASON;
    }

    /**
     * Carries out {@link Decision#TO_LIMBO}: suspends the lost backend and redirects to the waiting room.
     *
     * Unless there is nowhere to send them - the two cases below are the only ones {@link #decide} cannot see
     * from a {@code Component} and a {@code ServerKickResult} alone.
     */
    private void toLimbo(final KickedFromServerEvent event) {
        final String from = event.getServer().getServerInfo().getName();
        // Either waiting room, or a reasonless kick during a swap would bounce the player right back into it.
        if (servers.isWaitingRoom(from)) {
            // Redirecting back into the server that produced this event is the exact bounce this class stops.
            logger.warn(
                    "'{}' lost {} with no reason given, and it is itself a waiting room; "
                            + "leaving Velocity's own result in place",
                    from,
                    event.getPlayer().getUsername());
            return;
        }

        // The live room first, the standby only as a fallback - the same order PhaseRouting uses.
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

        // Scoped to this one backend; PackStation reads it through LimboHold's destinationAvailable.
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
