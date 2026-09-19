package eu.nordtal.s2.proxy.gate;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import eu.nordtal.s2.proxy.routing.PhaseServers;

import net.kyori.adventure.text.Component;

import org.slf4j.Logger;

import java.util.Locale;
import java.util.Objects;

/**
 * What happens to a player a backend has just kicked - shown their own reason if it gave one,
 * moved to the waiting room if it did not.
 *
 * <h2>The signal this reads</h2>
 * {@link KickedFromServerEvent#getServerKickReason()} is only ever present when the backend itself
 * sent an explicit {@code Disconnect} packet - a ban, an admin's {@code /kick}, a whitelist refusal,
 * or a backend's own graceful self-kick such as {@code smp.error.database-unreachable}. Checked
 * against the running proxy's own {@code velocity-4.2.0-30.jar} on 2026-09-16 (decompiled - the API
 * jar alone does not show it): a connection that instead <b>dies</b> - the container stops, it
 * crashes, Velocity loses the socket - reaches this event through a completely different path with
 * the reason argument passed as a literal {@code null}, precisely because nothing on that path ever
 * decided anything to tell the player. A present reason is therefore not merely usually a decision;
 * it is Velocity's own proof that one was made, on the one path capable of producing it. See
 * {@link #decide} for the branch this becomes.
 *
 * <h2>What each branch does</h2>
 * <ul>
 *   <li><b>A reason was given</b> - {@link Decision#SHOW_REASON}. Velocity wraps a backend's kick
 *       reason in one of its own sentences before it puts it on the screen -
 *       <i>"Kicked whilst connecting to smp: ..."</i> and <i>"Unable to connect to ..."</i>. Both are
 *       English, both are Velocity's translation bundle rather than ours, and neither can be reached
 *       from a message bundle in this repository. So the one screen a backend writes carefully -
 *       {@code smp.error.database-unreachable}, the whole point of which is to tell a player their
 *       progress is safe - arrived underneath a line naming an internal server name in the wrong
 *       language. This branch replaces the wrapper and disconnects with the backend's own component,
 *       unchanged since this class first existed: a kick somebody deliberately caused stays a
 *       disconnect, on the belief that a player who was banned or refused entry deserves to be told
 *       so plainly, not parked wordlessly in a room with no explanation.</li>
 *   <li><b>No reason at all</b> - {@link Decision#TO_LIMBO}, decided 2026-09-15
 *       (season-2-ops/20). Until then this class deliberately left Velocity's own result standing
 *       here, because there was nothing better to say. What Velocity's own result actually is on
 *       this path was never something this repository could observe - {@code Evacuation}'s own
 *       javadoc calls it "a behaviour nobody here has observed" - and in practice it lands a player
 *       on a disconnect screen: the network loses them even though the proxy is still standing.
 *       Now they are redirected into {@code limbo} instead, and {@link BackendHealth} suspends the
 *       backend that lost them so the ordinary waiting-room sweep - not a second kick - is what
 *       decides when they go back, which is what keeps a backend that immediately kicks again
 *       from bouncing them in a loop.</li>
 *   <li><b>Not a disconnect at all</b> - {@link Decision#LEAVE}. A {@code Notify} (the player is on
 *       another server and stays there) and a {@code RedirectPlayer} (something else has already
 *       chosen where they go) are left exactly as they are; touching either would move a player this
 *       class was never asked to move.</li>
 * </ul>
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
    public BackendKick(final ProxyServer proxy, final PhaseServers servers, final BackendHealth health,
                       final GateMessages messages, final LoginRoster roster, final Logger logger) {
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
        final Component reason = event.getServerKickReason().orElse(null);
        switch (decide(event.getResult(), reason)) {
            case SHOW_REASON ->
                    event.setResult(KickedFromServerEvent.DisconnectPlayer.create(reason));
            case TO_LIMBO -> toLimbo(event);
            case LEAVE -> {
                // Nothing: a Notify or a RedirectPlayer is somebody else's decision.
            }
        }
    }

    /**
     * The whole decision, as a function of the two things it depends on.
     *
     * <p>Static and separate because a {@link KickedFromServerEvent} cannot be built without a
     * {@code Player} and a {@code RegisteredServer}, which exist only on a running proxy - so the
     * handler above can be exercised nowhere and this can be exercised everywhere.</p>
     *
     * @param result what Velocity was going to do
     * @param reason the backend's own kick component, or {@code null} if it sent none
     * @return what to do instead
     */
    static Decision decide(final KickedFromServerEvent.ServerKickResult result, final Component reason) {
        if (!(result instanceof KickedFromServerEvent.DisconnectPlayer)) {
            return Decision.LEAVE;
        }
        return reason == null ? Decision.TO_LIMBO : Decision.SHOW_REASON;
    }

    /**
     * Carries out {@link Decision#TO_LIMBO}: suspends the backend that lost the player and redirects
     * them to the waiting room, unless there is nowhere to send them - the two cases below are the
     * only ones {@link #decide} cannot see from a {@code Component} and a {@code ServerKickResult}
     * alone.
     */
    private void toLimbo(final KickedFromServerEvent event) {
        final String from = event.getServer().getServerInfo().getName();
        // EITHER waiting room, since season-2-ops/120. Against `limbo` alone, a reasonless kick
        // out of `limbo-standby` during a swap would have fallen through to the redirect below and
        // sent the player into the room they had just been thrown out of - the exact bounce this
        // class exists to stop, and worse than the one it was written for, because the standby is
        // the room that is stopping last.
        if (servers.isWaitingRoom(from)) {
            // The waiting room itself just lost a player with no reason given. Redirecting them
            // back into the server that produced this event would be the exact bounce this class
            // exists to stop, so Velocity's own result stands - the same "nothing better to say"
            // rule this class always applied to a reasonless kick, still true for the one backend
            // that cannot be the destination of its own redirect.
            logger.warn("'{}' lost {} with no reason given, and it is itself a waiting room; "
                            + "leaving Velocity's own result in place",
                    from, event.getPlayer().getUsername());
            return;
        }

        // The live room first, the standby only because the live one is not registered right now -
        // the same order PhaseRouting uses, and for the same reason.
        final RegisteredServer target = proxy.getServer(servers.limbo())
                .or(() -> proxy.getServer(servers.limboStandby()))
                .orElse(null);
        if (target == null) {
            logger.error("{} lost its connection to '{}' with no reason given, and neither '{}' nor "
                            + "'{}' is registered on this proxy to hold them in instead",
                    event.getPlayer().getUsername(), from, servers.limbo(), servers.limboStandby());
            return;
        }

        // Scoped to the one backend named in this event. PackStation reads this through LimboHold's
        // destinationAvailable, which is what stops this player - and anybody else the sweep is
        // about to release toward the same backend - from being sent straight back into it before
        // BackendHealth's retry window has passed.
        health.suspend(from);
        logger.warn("{} lost its connection to '{}' with no reason given; moving them to '{}' "
                        + "instead of a disconnect screen, and holding '{}' suspended for {}s",
                event.getPlayer().getUsername(), from, target.getServerInfo().getName(), from,
                BackendHealth.RETRY.toSeconds());
        final Locale locale = roster.localeOf(event.getPlayer().getUniqueId());
        event.setResult(KickedFromServerEvent.RedirectPlayer.create(target,
                messages.connectionLost(locale)));
    }
}
