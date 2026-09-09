package eu.nordtal.s2.networkcontrol.pack;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.limbo.LimboProtocol;
import eu.nordtal.s2.networkcontrol.config.PackSpec;
import eu.nordtal.s2.networkcontrol.gate.LoginRoster;
import eu.nordtal.s2.networkcontrol.phase.PhaseWatch;
import eu.nordtal.s2.networkcontrol.routing.PhaseRouting;

import net.kyori.adventure.text.Component;

import org.slf4j.Logger;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The resource-pack station, and with it the second half of the login path: every player waits in
 * {@code limbo} until the pack is on their machine and the phase's backend will have them, and only
 * then is connected onward.
 *
 * <h2>The sequence being described</h2>
 * <ol>
 *   <li>{@code PlayerRouter} sets {@code limbo} as the initial server for every admitted login.</li>
 *   <li>{@link #onServerPostConnect} sees the player arrive on {@code limbo}, sends them the forced
 *       pack offer with its URL and SHA-1, and tells {@code limbo} what title to show.</li>
 *   <li>{@code limbo} answers {@link LimboProtocol.Type#READY} once the player has finished
 *       joining it.</li>
 *   <li>{@link #onPackStatus} sees {@code SUCCESSFUL} - or one of the failures, each with its own
 *       screen.</li>
 *   <li>When nothing is left to wait for ({@link WaitingBook}, which asks {@link LimboHold}) the
 *       player is handed to the release callback, which is {@code PlayerRouter}'s connect.</li>
 * </ol>
 *
 * <p><b>Those steps do not happen in that order.</b> Velocity dispatches the arrival, the pack
 * status and {@code limbo}'s answer on different threads with no ordering between them, and step 3
 * routinely beats step 2 - which is what {@link WaitingBook} exists for. Every handler below records
 * a fact and then re-asks the whole question; none may assume what has already happened.</p>
 *
 * <p><b>The backend never decides where a player goes</b>, or the routing rules would live in two
 * processes. {@code limbo}'s message says "this player is ready" and carries no destination; this
 * class asks {@link PhaseRouting} where they belong.</p>
 *
 * <p><b>A plugin message is not evidence of who sent it.</b> Registering a channel makes the proxy
 * advertise it to the client, and a modded client can write whatever bytes it likes onto it - a
 * forged {@code READY} is a player releasing themselves from the waiting room, which is to say
 * skipping the resource pack. Every message is rejected unless {@link PluginMessageEvent#getSource()}
 * is a {@link ServerConnection}, and consumed either way.</p>
 *
 * <p>The pack is offered here rather than on {@code limbo} so that there is one offer in one place
 * and a player moved between backends is not asked twice.</p>
 *
 * <p>Velocity fires {@code @Subscribe} handlers off the Netty threads, and every piece of per-player
 * state is in {@link WaitingBook}, which makes a release happen exactly once. Nothing here touches
 * the database: the phase comes from {@link PhaseWatch} and the language from {@link LoginRoster}.
 */
public final class PackStation {

    private final ProxyServer proxy;
    private final Logger logger;
    private final PhaseRouting routing;
    private final PhaseWatch phases;
    private final LoginRoster roster;
    private final PackMessages messages;
    private final PackSpec config;
    private final WaitingBook book;

    /** {@code null} when {@code pack.yml#enabled} is off - the one thing that makes the wait short. */
    private final PackOffer offer;

    private final MinecraftChannelIdentifier channel =
            MinecraftChannelIdentifier.from(LimboProtocol.CHANNEL);

    /** UUIDs we have already complained about sending us a forged message, so a spammer logs once. */
    private final Set<UUID> reportedForgery = ConcurrentHashMap.newKeySet();

    private volatile Consumer<Player> release = player -> { };

    /**
     * Whether an update run currently has that backend stopped.
     *
     * <p>A seam and not a lookup, so this class keeps knowing nothing about the update table. It
     * answers false until {@link #whenUpdating} is called, which is what an evaluation during
     * startup - or on a proxy with the update watch switched off - gets: the ordinary
     * {@code BACKEND} title, exactly as before this existed.</p>
     */
    private volatile java.util.function.Predicate<String> updating = server -> false;

    public PackStation(final ProxyServer proxy, final Logger logger, final PhaseRouting routing,
                       final PhaseWatch phases, final LoginRoster roster, final PackMessages messages,
                       final PackSpec config, final PackOffer offer, final WaitingBook book) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.routing = Objects.requireNonNull(routing, "routing");
        this.phases = Objects.requireNonNull(phases, "phases");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.config = Objects.requireNonNull(config, "config");
        this.book = Objects.requireNonNull(book, "book");
        this.offer = offer;
    }

    /**
     * Registers {@code nordtal:limbo} with the proxy. Without this the proxy forwards the channel
     * blindly and never sees a message on it.
     */
    public void registerChannel() {
        proxy.getChannelRegistrar().register(channel);
    }

    /**
     * @param release what to do with a player who has finished waiting; in production this is
     *                {@code PlayerRouter::releaseFromLimbo}. Set after construction because the
     *                router needs this station and this station needs the router
     */
    public void onRelease(final Consumer<Player> release) {
        this.release = Objects.requireNonNull(release, "release");
    }

    /**
     * @param updating asked, per destination, whether an update run has that backend stopped; in
     *                 production this is {@code Evacuation::isMoving}. Set after construction for
     *                 the same reason {@link #onRelease} is: the watch is built from a pool this
     *                 class never sees
     */
    public void whenUpdating(final java.util.function.Predicate<String> updating) {
        this.updating = Objects.requireNonNull(updating, "updating");
    }

    /**
     * @param uuid a connected player
     * @return whether they are currently being held in the waiting room. {@code PlayerRouter} asks
     *         so that a phase change re-examines the hold instead of connecting somebody whose pack
     *         is still downloading
     */
    public boolean isHeld(final UUID uuid) {
        return book.isWaiting(uuid);
    }

    // ------------------------------------------------------------------ arriving in the waiting room

    @Subscribe
    public void onServerPostConnect(final ServerPostConnectEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();

        if (!onLimbo(player)) {
            // They have left the waiting room - released by us, or moved by a phase change. The
            // session's facts survive; this visit's do not.
            book.left(uuid);
            return;
        }

        book.entered(uuid);
        sendOfferIfNeeded(player);
        evaluate(player);
    }

    private void sendOfferIfNeeded(final Player player) {
        if (offer == null) {
            return;
        }
        if (!book.claimOffer(player.getUniqueId())) {
            return;
        }
        player.sendResourcePackOffer(offer.forLocale(localeOf(player)));
        logger.debug("Offered the resource pack to {}", player.getUsername());
    }

    // ------------------------------------------------------------------ the client's answer

    @Subscribe
    public void onPackStatus(final PlayerResourcePackStatusEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        final Locale locale = localeOf(player);

        switch (event.getStatus()) {
            case SUCCESSFUL -> {
                book.packApplied(uuid);
                evaluate(player);
            }
            case ACCEPTED, DOWNLOADED -> {
                // Intermediate. The player is still working on it and the waiting room already says
                // so; there is nothing to change and nothing to log per player.
            }
            case DECLINED -> {
                logger.info("{} declined the resource pack", player.getUsername());
                disconnect(player, messages.declined(locale));
            }
            case FAILED_DOWNLOAD, FAILED_RELOAD -> {
                logger.warn("{} could not apply the resource pack: {}", player.getUsername(),
                        event.getStatus());
                disconnect(player, messages.failedDownload(locale));
            }
            case INVALID_URL -> {
                // Everybody's problem, not this player's: pack.yml#url does not load at all.
                logger.error("The client of {} reports pack.yml#url as unloadable. EVERY player will "
                        + "fail this way until it is fixed.", player.getUsername());
                disconnect(player, messages.invalidUrl(locale));
            }
            case DISCARDED -> {
                // The pack was removed rather than refused. Nothing removes it in this design, so
                // this is only reachable if a backend sends its own pack over ours.
                logger.warn("The resource pack was discarded for {}", player.getUsername());
            }
        }
    }

    // ------------------------------------------------------------------ limbo's answer

    @Subscribe
    public void onPluginMessage(final PluginMessageEvent event) {
        if (!channel.equals(event.getIdentifier())) {
            return;
        }

        // Consumed whatever it turns out to be: this conversation is between the proxy and limbo,
        // and nothing downstream of either has any business seeing it.
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (!(event.getSource() instanceof ServerConnection connection)) {
            reportForgery(event);
            return;
        }

        final Optional<LimboProtocol.Message> message = LimboProtocol.decode(event.getData());
        if (message.isEmpty()) {
            logger.warn("Dropped an unreadable {} message from '{}'", LimboProtocol.CHANNEL,
                    connection.getServerInfo().getName());
            return;
        }
        if (message.get().type() != LimboProtocol.Type.READY) {
            // WAIT runs proxy -> limbo only. A backend sending one is a bug in that backend.
            logger.warn("'{}' sent a {} on {}, which only the proxy sends",
                    connection.getServerInfo().getName(), message.get().type(), LimboProtocol.CHANNEL);
            return;
        }

        final Player player = connection.getPlayer();
        if (book.ready(player.getUniqueId())) {
            // The arrival event has not reached us yet. Logged at INFO because it is the only
            // evidence that this race really happens.
            logger.info("'{}' reported {} ready before the proxy had finished putting them in the "
                            + "waiting room; remembered rather than dropped",
                    connection.getServerInfo().getName(), player.getUsername());
        }
        evaluate(player);
    }

    private void reportForgery(final PluginMessageEvent event) {
        // A client writing on this channel is trying to release itself from the waiting room, which
        // is to say skip the resource pack. Logged once per player so that a loop cannot fill a
        // disk, and never acted on.
        final UUID uuid = event.getSource() instanceof Player player ? player.getUniqueId() : null;
        if (uuid == null || reportedForgery.add(uuid)) {
            logger.warn("Ignored a {} message that did not come from a backend server: {}",
                    LimboProtocol.CHANNEL, event.getSource());
        }
    }

    // ------------------------------------------------------------------ the decision

    /**
     * Re-asks the question for every player currently in the waiting room.
     * <p>
     * Driven by {@code gate.yml#limbo-sweep-interval-seconds}, because one of the three reasons a
     * player waits - the phase's backend not being there - has no event to announce that it is
     * over. It is also what enforces {@code pack.yml#apply-timeout-seconds}.
     * </p>
     *
     * @return how many players were looked at
     */
    public int sweep() {
        int seen = 0;
        for (final Player player : proxy.getAllPlayers()) {
            if (book.isWaiting(player.getUniqueId())) {
                evaluate(player);
                seen++;
            }
        }
        return seen;
    }

    /**
     * Looks at one held player and carries out whatever {@link WaitingBook} says about them.
     * <p>
     * Everything that is a rule lives in the book and everything that is a Velocity call lives here,
     * which is what makes the rule assertable at all. The one decision left in this method is
     * whether the player is still on {@code limbo}, because that is a question about a connection.
     * </p>
     *
     * @param player a connected player; doing nothing for one this station is not holding
     */
    public void evaluate(final Player player) {
        final UUID uuid = player.getUniqueId();
        if (!book.isWaiting(uuid)) {
            return;
        }
        if (!onLimbo(player)) {
            book.left(uuid);
            return;
        }

        final SeasonPhase phase = phases.lastKnown();
        // The admin flag decides two things: maintenance does not hold an admin, and an admin
        // released while the network is closed goes to the SMP rather than back into this room.
        final boolean admin = roster.isAdmin(uuid);
        final String destination = routing.servers().forAdmitted(phase, admin);
        final WaitingDecision decision = book.decide(uuid, phase, admin,
                proxy.getServer(destination).isPresent(), destination, updating.test(destination));

        switch (decision.action()) {
            case IDLE -> {
                // Already looking at the right title, or waiting out the grace period.
            }
            case SHOW -> sendToLimbo(player, LimboProtocol.wait(decision.reason()));
            case TIMED_OUT -> {
                logger.warn("{} never answered the resource pack offer within {}s",
                        player.getUsername(), config.applyTimeoutSeconds());
                disconnect(player, messages.timedOut(localeOf(player)));
            }
            case RELEASE -> {
                logger.info("{} has the pack and is leaving the waiting room for '{}'",
                        player.getUsername(), destination);
                release.accept(player);
            }
            case RELEASE_UNCONFIRMED -> {
                // The player goes where they were always going; what is wrong is the channel, and
                // this is the only place that would say so.
                logger.warn("Releasing {} to '{}' without a READY from '{}': everything else has "
                                + "been settled for the grace period. The nordtal:limbo channel is "
                                + "not delivering backend messages to this proxy.",
                        player.getUsername(), destination, routing.servers().limbo());
                release.accept(player);
            }
        }
    }

    private void sendToLimbo(final Player player, final byte[] data) {
        player.getCurrentServer().ifPresent(connection -> {
            if (!connection.sendPluginMessage(channel, data)) {
                // The backend has not registered the channel: a limbo that is running without its
                // plugin, or an older one. The player is not stuck - the release path does not
                // depend on WAIT - but their screen will say nothing useful.
                logger.warn("'{}' did not accept a {} message; is the limbo plugin running there?",
                        connection.getServerInfo().getName(), LimboProtocol.CHANNEL);
            }
        });
    }

    /**
     * Called by the router when the connection a release asked for failed: the backend is registered
     * and not answering. The player is still on limbo, so they go back on the books with the
     * {@code BACKEND} title and the release is tried again in {@link WaitingBook#RELEASE_RETRY}.
     *
     * @param player the player the release could not move
     * @param cause  why, in one line - a backend that is restarting says "Connection refused"
     */
    public void releaseFailed(final Player player, final String cause) {
        final String destination = routing.servers()
                .forAdmitted(phases.lastKnown(), roster.isAdmin(player.getUniqueId()));
        book.releaseFailed(player.getUniqueId(), destination);
        logger.warn("'{}' did not take {} ({}); holding them in the waiting room and trying again in {}s",
                destination, player.getUsername(), cause, WaitingBook.RELEASE_RETRY.toSeconds());
        evaluate(player);
    }

    // ------------------------------------------------------------------ housekeeping

    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        final UUID uuid = event.getPlayer().getUniqueId();
        book.forget(uuid);
        reportedForgery.remove(uuid);
    }

    private boolean onLimbo(final Player player) {
        return player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .filter(name -> name.equals(routing.servers().limbo()))
                .isPresent();
    }

    private Locale localeOf(final Player player) {
        return roster.localeOf(player.getUniqueId());
    }

    private void disconnect(final Player player, final Component reason) {
        // Ending the visit before the disconnect, so a sweep running concurrently on another thread
        // cannot decide anything else about somebody who is already on their way out.
        book.left(player.getUniqueId());
        player.disconnect(reason);
    }
}
