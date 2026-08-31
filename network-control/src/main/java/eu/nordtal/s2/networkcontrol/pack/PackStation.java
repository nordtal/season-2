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
import eu.nordtal.s2.common.limbo.WaitReason;
import eu.nordtal.s2.networkcontrol.config.PackSpec;
import eu.nordtal.s2.networkcontrol.gate.LoginRoster;
import eu.nordtal.s2.networkcontrol.phase.PhaseWatch;
import eu.nordtal.s2.networkcontrol.routing.PhaseRouting;

import net.kyori.adventure.text.Component;

import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The resource-pack station, and with it the second half of docs/architecture.md's login path:
 * every player waits in {@code limbo} until the pack is on their machine and the phase's backend
 * will have them, and only then is connected onward.
 *
 * <h2>The sequence, in the order it actually happens</h2>
 * <ol>
 *   <li>{@code PlayerRouter} sets {@code limbo} as the initial server for every admitted login.</li>
 *   <li>{@link #onServerPostConnect} sees the player arrive on {@code limbo}, sends them the forced
 *       pack offer with its URL and SHA-1, and tells {@code limbo} what title to show.</li>
 *   <li>{@code limbo} answers {@link LimboProtocol.Type#READY} once the player has finished
 *       joining it.</li>
 *   <li>{@link #onPackStatus} sees {@code SUCCESSFUL} - or one of the failures, each with its own
 *       screen.</li>
 *   <li>When nothing is left to wait for ({@link LimboHold}) the player is handed to the release
 *       callback, which is {@code PlayerRouter}'s connect.</li>
 * </ol>
 *
 * <h2>The backend never decides where a player goes</h2>
 * docs/season-phases.md#routing is explicit: "a backend must not be able to decide it wants a
 * player somewhere - that would put the routing rules in two processes". So {@code limbo}'s message
 * says <em>"this player is ready"</em> and nothing else; it carries no destination, and this class
 * asks {@link PhaseRouting} where the player belongs. A {@code limbo} that wanted to send somebody
 * to the SMP could not express it.
 *
 * <h2>A plugin message is not evidence of who sent it</h2>
 * Registering a channel makes the proxy advertise it to the <b>client</b>, and a modded client can
 * write whatever bytes it likes onto it. A forged {@code READY} is a player releasing themselves
 * from the waiting room, which is to say skipping the resource pack. Every message on this channel
 * is therefore rejected unless {@link PluginMessageEvent#getSource()} is a {@link ServerConnection}
 * - and it is consumed either way, so it never reaches the client or another backend.
 *
 * <h2>Why the pack is offered here and not on {@code limbo}</h2>
 * Because docs/architecture.md puts it on the proxy: one offer, one place, and a player who is
 * moved between backends is not asked twice. The written fallback, if a forced offer from the
 * proxy turns out to misbehave, is for {@code limbo} to offer it on join instead - see
 * docs/operations.md#open-verification. That is a change to this class and to
 * {@code limbo}, not to the design.
 *
 * <h2>Threading</h2>
 * Velocity fires {@code @Subscribe} handlers off the Netty threads, and the state here is two
 * concurrent maps and a set. Nothing in this class touches the database: the phase comes from
 * {@link PhaseWatch}'s in-memory value and the language from {@link LoginRoster}, both filled in by
 * the login query the gate already made.
 */
public final class PackStation {

    /** Per session: what we have offered this player and whether they applied it. */
    private static final class Offered {
        private volatile Instant at;
        private volatile boolean applied;
    }

    /** Per visit to the waiting room: what limbo has told us and what it is currently showing. */
    private static final class Hold {
        private volatile boolean ready;
        private volatile WaitReason shown;
    }

    private final ProxyServer proxy;
    private final Logger logger;
    private final PhaseRouting routing;
    private final PhaseWatch phases;
    private final LoginRoster roster;
    private final PackMessages messages;
    private final PackSpec config;
    private final Clock clock;

    /** {@code null} when {@code pack.yml#enabled} is off - the one thing that makes the wait short. */
    private final PackOffer offer;

    private final MinecraftChannelIdentifier channel =
            MinecraftChannelIdentifier.from(LimboProtocol.CHANNEL);

    private final ConcurrentHashMap<UUID, Offered> offers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Hold> holds = new ConcurrentHashMap<>();

    /** UUIDs we have already complained about sending us a forged message, so a spammer logs once. */
    private final Set<UUID> reportedForgery = ConcurrentHashMap.newKeySet();

    private volatile Consumer<Player> release = player -> { };

    public PackStation(final ProxyServer proxy, final Logger logger, final PhaseRouting routing,
                       final PhaseWatch phases, final LoginRoster roster, final PackMessages messages,
                       final PackSpec config, final PackOffer offer, final Clock clock) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.routing = Objects.requireNonNull(routing, "routing");
        this.phases = Objects.requireNonNull(phases, "phases");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
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
     * @param uuid a connected player
     * @return whether they are currently being held in the waiting room. {@code PlayerRouter} asks
     *         so that a phase change re-examines the hold instead of connecting somebody whose pack
     *         is still downloading
     */
    public boolean isHeld(final UUID uuid) {
        return uuid != null && holds.containsKey(uuid);
    }

    // ------------------------------------------------------------------ arriving in the waiting room

    @Subscribe
    public void onServerPostConnect(final ServerPostConnectEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();

        if (!onLimbo(player)) {
            // They have left the waiting room - released by us, or moved by a phase change. The
            // offer state survives (it is per session), the hold does not.
            holds.remove(uuid);
            return;
        }

        holds.computeIfAbsent(uuid, ignored -> new Hold());
        sendOfferIfNeeded(player);
        evaluate(player);
    }

    private void sendOfferIfNeeded(final Player player) {
        if (offer == null) {
            return;
        }
        final Offered offered = offers.computeIfAbsent(player.getUniqueId(), ignored -> new Offered());
        if (offered.at != null || offered.applied) {
            // Once per session. A player bounced back into limbo by a phase change is not asked a
            // second time for a pack they already have.
            return;
        }
        offered.at = clock.instant();
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
                offers.computeIfAbsent(uuid, ignored -> new Offered()).applied = true;
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
        final Hold hold = holds.get(player.getUniqueId());
        if (hold == null) {
            // READY from a server the player is no longer on, or from one that is not limbo. Either
            // way there is no hold to release.
            return;
        }
        hold.ready = true;
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
            if (holds.containsKey(player.getUniqueId())) {
                evaluate(player);
                seen++;
            }
        }
        return seen;
    }

    /**
     * Looks at one held player and either updates what the waiting room says, releases them, or
     * disconnects them for never answering the offer.
     *
     * @param player a connected player; doing nothing for one this station is not holding
     */
    public void evaluate(final Player player) {
        final UUID uuid = player.getUniqueId();
        final Hold hold = holds.get(uuid);
        if (hold == null) {
            return;
        }
        if (!onLimbo(player)) {
            holds.remove(uuid);
            return;
        }

        final Offered offered = offers.get(uuid);
        final boolean applied = offered != null && offered.applied;
        if (offer != null && !applied && timedOut(offered)) {
            logger.warn("{} never answered the resource pack offer within {}s", player.getUsername(),
                    config.applyTimeoutSeconds());
            disconnect(player, messages.timedOut(localeOf(player)));
            return;
        }

        final SeasonPhase phase = phases.lastKnown();
        final String destination = routing.servers().forPhase(phase);
        final Optional<WaitReason> waiting = LimboHold.reason(offer == null || applied, phase,
                proxy.getServer(destination).isPresent());

        if (waiting.isPresent()) {
            show(player, hold, waiting.get());
            return;
        }
        if (!hold.ready) {
            // Nothing left to wait for except limbo itself saying the player has finished joining
            // it. Deliberately silent - see LimboHold's own documentation for why no title is sent.
            return;
        }

        holds.remove(uuid);
        logger.info("{} has the pack and is leaving the waiting room for '{}'", player.getUsername(),
                destination);
        release.accept(player);
    }

    private void show(final Player player, final Hold hold, final WaitReason reason) {
        if (reason == hold.shown) {
            // The sweep runs every few seconds; re-sending an unchanged reason would make limbo
            // re-issue the same title on a loop.
            return;
        }
        hold.shown = reason;
        sendToLimbo(player, LimboProtocol.wait(reason));
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

    // ------------------------------------------------------------------ housekeeping

    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        final UUID uuid = event.getPlayer().getUniqueId();
        holds.remove(uuid);
        offers.remove(uuid);
        reportedForgery.remove(uuid);
    }

    private boolean timedOut(final Offered offered) {
        return offered != null && offered.at != null
                && Duration.between(offered.at, clock.instant()).getSeconds() >= config.applyTimeoutSeconds();
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
        holds.remove(player.getUniqueId());
        player.disconnect(reason);
    }
}
