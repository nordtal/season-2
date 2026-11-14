package eu.nordtal.season.proxy.pack;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.limboprotocol.LimboProtocol;
import eu.nordtal.season.limboprotocol.WaitReason;
import eu.nordtal.season.proxy.config.PackSpec;
import eu.nordtal.season.proxy.gate.BackendHealth;
import eu.nordtal.season.proxy.gate.LoginRoster;
import eu.nordtal.season.proxy.routing.SeasonRouting;
import eu.nordtal.season.proxy.season.SeasonWatch;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Keeps each player in {@code limbo} until the pack is applied and the server of the active mode accepts them.
 * A changed pack is offered to every player there at once. Each handler records a fact and asks {@link WaitingBook}.
 */
public final class PackStation {

    /** The answers that disconnect the player when they are about the newest offer. */
    private static final Set<PlayerResourcePackStatusEvent.Status> KICKS = Set.of(
            PlayerResourcePackStatusEvent.Status.DECLINED,
            PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD,
            PlayerResourcePackStatusEvent.Status.FAILED_RELOAD);

    private final ProxyServer proxy;
    private final Logger logger;
    private final SeasonRouting routing;
    private final SeasonWatch seasons;
    private final LoginRoster roster;
    private final PackMessages messages;
    private final Supplier<PackSpec> config;
    private final WaitingBook book;
    private final BackendHealth health;

    /** The offer of the pack the settings name, made again when they change. */
    private volatile @Nullable PackOffer offer;

    private final MinecraftChannelIdentifier channel = MinecraftChannelIdentifier.from(LimboProtocol.CHANNEL);

    /** The players whose forged message was logged. */
    private final Set<UUID> reportedForgery = ConcurrentHashMap.newKeySet();

    private volatile Consumer<Player> release = player -> {};

    /** Whether an update run has stopped the backend. */
    private volatile java.util.function.Predicate<String> updating = server -> false;

    /** Whether an admin keeps the backend stopped. */
    private volatile java.util.function.Predicate<String> held = server -> false;

    /** Whether a mode switch holds every player. */
    private volatile BooleanSupplier switching = () -> false;

    /** How far the running update is. */
    private volatile Supplier<OptionalInt> progress = OptionalInt::empty;

    public PackStation(
            final ProxyServer proxy,
            final Logger logger,
            final SeasonRouting routing,
            final SeasonWatch seasons,
            final LoginRoster roster,
            final PackMessages messages,
            final Supplier<PackSpec> config,
            final WaitingBook book,
            final BackendHealth health) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.routing = Objects.requireNonNull(routing, "routing");
        this.seasons = Objects.requireNonNull(seasons, "seasons");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.config = Objects.requireNonNull(config, "config");
        this.book = Objects.requireNonNull(book, "book");
        this.health = Objects.requireNonNull(health, "health");
    }

    /** Registers {@code nordtal:limbo}. */
    public void registerChannel() {
        proxy.getChannelRegistrar().register(channel);
    }

    /** Sets the action for a player who finished waiting. */
    public void onRelease(final Consumer<Player> release) {
        this.release = Objects.requireNonNull(release, "release");
    }

    /** Sets the check whether an update run has stopped a backend. */
    public void whenUpdating(final java.util.function.Predicate<String> updating) {
        this.updating = Objects.requireNonNull(updating, "updating");
    }

    /** Sets the check whether a mode switch holds every player. */
    public void whenSwitching(final BooleanSupplier switching) {
        this.switching = Objects.requireNonNull(switching, "switching");
    }

    /** Returns whether a mode switch holds every player. */
    public boolean isSwitching() {
        return switching.getAsBoolean();
    }

    /** Sets the source of the percentage of the running update. */
    public void whenProgressing(final Supplier<OptionalInt> progress) {
        this.progress = Objects.requireNonNull(progress, "progress");
    }

    /** Sets the check whether an admin keeps a backend stopped. */
    public void whenHeld(final java.util.function.Predicate<String> held) {
        this.held = Objects.requireNonNull(held, "held");
    }

    /** Returns whether the player waits in the waiting room. */
    public boolean isHeld(final UUID uuid) {
        return book.isWaiting(uuid);
    }

    // arriving in the waiting room

    @Subscribe
    public void onServerPostConnect(final ServerPostConnectEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();

        if (!onLimbo(player)) {
            book.left(uuid);
            return;
        }

        book.entered(uuid);
        sendOfferIfNeeded(player);
        evaluate(player);
    }

    /**
     * Offers a changed pack at once to every player already in the waiting room, as a new arrival gets it.
     * Players elsewhere keep their pack until their next visit there, or until a live swap on the SMP.
     *
     * @return the number of players offered the pack
     */
    public int packChanged() {
        int offered = 0;
        for (final Player player : proxy.getAllPlayers()) {
            if (onLimbo(player) && sendOfferIfNeeded(player)) {
                logger.info(
                        "changed resource pack offered: player={} sha1={}",
                        player.getUsername(),
                        config.get().sha1());
                evaluate(player);
                offered++;
            }
        }
        return offered;
    }

    /** Offers the pack unless the player is exempt, has it or waits for its answer, and returns whether it did. */
    private boolean sendOfferIfNeeded(final Player player) {
        final PackSpec spec = config.get();
        if (!spec.enabled()) {
            return false;
        }
        if (roster.isPackExempt(player.getUniqueId())) {
            if (book.packExempt(player.getUniqueId())) {
                logger.info("resource pack offer skipped, player exempt: player={}", player.getUsername());
            }
            return false;
        }
        final PackOffer current = offerOf(spec);
        if (!book.claimOffer(player.getUniqueId(), current.sha1())) {
            return false;
        }
        final ResourcePackInfo offer = current.forLocale(localeOf(player));
        if (player.getAppliedResourcePacks().stream()
                .anyMatch(pack -> Arrays.equals(pack.getHash(), offer.getHash()))) {
            // Velocity refuses an offer of a pack it already counts as applied, so the book takes it as applied.
            logger.info("resource pack already applied: player={} sha1={}", player.getUsername(), current.sha1());
            replace(player, current.sha1());
            return false;
        }
        player.sendResourcePackOffer(offer);
        logger.debug("resource pack offered: player={} sha1={}", player.getUsername(), current.sha1());
        return true;
    }

    /** Returns the offer of the pack {@code spec} names, keeping the last one while the settings stay. */
    private PackOffer offerOf(final PackSpec spec) {
        final PackOffer last = offer;
        if (last != null && last.offers(spec)) {
            return last;
        }
        final PackOffer made = new PackOffer(proxy, spec, messages);
        offer = made;
        return made;
    }

    /** Records the applied pack and removes the one it replaced from the client. */
    private void applied(final Player player, final @Nullable UUID packId) {
        final UUID uuid = player.getUniqueId();
        if (!book.answersNewestOffer(uuid, packId)) {
            // A newer offer replaced this pack before it was applied, so it goes again at once.
            book.takeReplaced(uuid, packId)
                    .ifPresentOrElse(
                            replaced -> {
                                player.removeResourcePacks(PackOffer.idOf(replaced));
                                logger.info(
                                        "replaced resource pack removed: player={} sha1={}",
                                        player.getUsername(),
                                        replaced);
                            },
                            () -> logger.debug(
                                    "resource pack applied that is not the newest offer: player={}",
                                    player.getUsername()));
            return;
        }
        replace(player, book.offeredSha1(uuid).orElseThrow());
    }

    /** Records {@code sha1} as applied and removes the pack it replaced from the client. */
    private void replace(final Player player, final String sha1) {
        book.packApplied(player.getUniqueId(), sha1).ifPresent(before -> {
            player.removeResourcePacks(PackOffer.idOf(before));
            logger.info("resource pack replaced: player={} sha1={}", player.getUsername(), sha1);
        });
    }

    // the client's answer

    @Subscribe
    public void onPackStatus(final PlayerResourcePackStatusEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        final Locale locale = localeOf(player);

        // An answer about a pack a newer offer replaced keeps the player, as SUCCESSFUL and DISCARDED do.
        if (KICKS.contains(event.getStatus()) && !book.answersNewestOffer(uuid, event.getPackId())) {
            logger.debug(
                    "answer about a replaced resource pack ignored: player={} status={}",
                    player.getUsername(),
                    event.getStatus());
            return;
        }
        switch (event.getStatus()) {
            case SUCCESSFUL -> {
                applied(player, event.getPackId());
                evaluate(player);
            }
            case ACCEPTED, DOWNLOADED -> {}
            case DECLINED -> {
                logger.info("resource pack declined: player={}", player.getUsername());
                disconnect(player, messages.declined(locale));
            }
            case FAILED_DOWNLOAD, FAILED_RELOAD -> {
                logger.warn("resource pack not applied: player={} status={}", player.getUsername(), event.getStatus());
                disconnect(player, messages.failedDownload(locale));
            }
            case INVALID_URL -> {
                logger.error("resource pack url unloadable: player={}", player.getUsername());
                disconnect(player, messages.invalidUrl(locale));
            }
            case DISCARDED -> {
                if (book.offeredSha1(uuid).map(PackOffer::idOf).equals(Optional.ofNullable(event.getPackId()))) {
                    logger.warn("resource pack discarded: player={}", player.getUsername());
                } else {
                    logger.debug("replaced resource pack discarded: player={}", player.getUsername());
                }
            }
        }
    }

    // limbo's answer

    @Subscribe
    public void onPluginMessage(final PluginMessageEvent event) {
        if (!channel.equals(event.getIdentifier())) {
            return;
        }

        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (!(event.getSource() instanceof ServerConnection connection)) {
            reportForgery(event);
            return;
        }

        final Optional<LimboProtocol.Message> message = LimboProtocol.decode(event.getData());
        if (message.isEmpty()) {
            logger.warn(
                    "unreadable plugin message dropped: plugin_channel={} server={}",
                    LimboProtocol.CHANNEL,
                    connection.getServerInfo().getName());
            return;
        }
        if (message.get().type() != LimboProtocol.Type.READY) {
            logger.warn(
                    "unexpected plugin message ignored: server={} type={} plugin_channel={}",
                    connection.getServerInfo().getName(),
                    message.get().type(),
                    LimboProtocol.CHANNEL);
            return;
        }

        final Player player = connection.getPlayer();
        if (book.ready(player.getUniqueId())) {
            logger.info(
                    "early ready remembered: server={} player={}",
                    connection.getServerInfo().getName(),
                    player.getUsername());
        }
        evaluate(player);
    }

    private void reportForgery(final PluginMessageEvent event) {
        final UUID uuid = event.getSource() instanceof Player player ? player.getUniqueId() : null;
        if (uuid == null || reportedForgery.add(uuid)) {
            logger.warn(
                    "plugin message from non-backend ignored: plugin_channel={} source={}",
                    LimboProtocol.CHANNEL,
                    event.getSource() instanceof Player player
                            ? player.getUsername()
                            : event.getSource().getClass().getSimpleName());
        }
    }

    // the decision

    /**
     * Evaluates every player in the waiting room. This enforces the pack timeout.
     *
     * @return the number of evaluated players
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

    /** Sweeps the waiting room while a player waits for a slot onto {@code smp} or an update shows its progress. */
    public void sweepBusy() {
        if (book.anyQueued() || progress.get().isPresent()) {
            final int _ = sweep();
        }
    }

    /** Executes the decision of {@link WaitingBook} for the player. */
    public void evaluate(final Player player) {
        final UUID uuid = player.getUniqueId();
        if (!book.isWaiting(uuid)) {
            return;
        }
        if (!onLimbo(player)) {
            book.left(uuid);
            return;
        }

        final SeasonState season = seasons.state();
        final boolean admin = roster.isAdmin(uuid);
        final String destination = routing.servers().forMode(season.mode());
        final boolean available = proxy.getServer(destination).isPresent() && !health.isSuspended(destination);
        final WaitingDecision decision = book.decide(
                uuid,
                holdOf(season, switching.getAsBoolean()),
                admin,
                available,
                destination,
                updating.test(destination),
                held.test(destination));

        final OptionalInt percent = progress.get();
        switch (decision.action()) {
            case IDLE -> {
                if (percent.isPresent() && book.claimProgress(uuid, percent.getAsInt())) {
                    sendToLimbo(player, LimboProtocol.wait(WaitReason.UPDATE, percent.getAsInt()));
                }
            }
            case SHOW -> {
                final WaitReason reason = Objects.requireNonNull(decision.reason(), "SHOW always carries a reason");
                final boolean numbered = percent.isPresent() && book.claimProgress(uuid, percent.getAsInt());
                sendToLimbo(player, LimboProtocol.wait(reason, numbered ? percent.getAsInt() : null));
            }
            case TIMED_OUT -> {
                logger.warn(
                        "resource pack offer not answered: player={} seconds={}",
                        player.getUsername(),
                        config.get().applyTimeoutSeconds());
                disconnect(player, messages.timedOut(localeOf(player)));
            }
            case RELEASE -> {
                logger.info("player released from waiting room: player={} to={}", player.getUsername(), destination);
                release.accept(player);
            }
            case RELEASE_UNCONFIRMED -> {
                logger.warn(
                        "player released without ready: player={} to={} waiting_room={}",
                        player.getUsername(),
                        destination,
                        routing.servers().limbo());
                release.accept(player);
            }
        }
    }

    private void sendToLimbo(final Player player, final byte[] data) {
        player.getCurrentServer().ifPresent(connection -> {
            if (!connection.sendPluginMessage(channel, data)) {
                logger.warn(
                        "plugin message not accepted: server={} plugin_channel={}",
                        connection.getServerInfo().getName(),
                        LimboProtocol.CHANNEL);
            }
        });
    }

    /** Returns the player to the waiting list after a failed release connection and suspends the backend. */
    public void releaseFailed(final Player player, final String cause) {
        final String destination = routing.servers().forMode(seasons.state().mode());
        book.releaseFailed(player.getUniqueId(), destination);
        health.suspend(destination);
        logger.warn(
                "release not taken, player held: to={} player={} seconds={} cause={}",
                destination,
                player.getUsername(),
                WaitingBook.RELEASE_RETRY.toSeconds(),
                cause);
        evaluate(player);
    }

    /** Returns what the season holds: a switch before maintenance. */
    static SeasonHold holdOf(final SeasonState season, final boolean switching) {
        if (switching) {
            return SeasonHold.SWITCH;
        }
        return season.maintenance() ? SeasonHold.MAINTENANCE : SeasonHold.NONE;
    }

    /** Puts a player whom the gate lets wait back on the waiting list, with the maintenance screen. */
    public void holdAgain(final Player player) {
        if (onLimbo(player) && book.heldAgain(player.getUniqueId(), WaitReason.MAINTENANCE)) {
            sendToLimbo(player, LimboProtocol.wait(WaitReason.MAINTENANCE, null));
        }
    }

    // housekeeping

    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        final UUID uuid = event.getPlayer().getUniqueId();
        book.forget(uuid);
        reportedForgery.remove(uuid);
    }

    /** Returns whether the player is on {@code limbo} or {@code limbo-standby}. */
    private boolean onLimbo(final Player player) {
        return player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .filter(name -> routing.servers().isWaitingRoom(name))
                .isPresent();
    }

    private Locale localeOf(final Player player) {
        return roster.localeOf(player.getUniqueId());
    }

    private void disconnect(final Player player, final Component reason) {
        book.left(player.getUniqueId());
        player.disconnect(reason);
    }
}
