package eu.nordtal.s2.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;

import com.zaxxer.hikari.HikariDataSource;

import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.health.Readiness;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.ToneColours;
import eu.nordtal.s2.common.online.OnlineDirectory;
import eu.nordtal.s2.common.online.OnlineRoster;
import eu.nordtal.s2.common.phase.PhaseDirectory;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.proxy.config.ColoursSpec;
import eu.nordtal.s2.proxy.config.Configs;
import eu.nordtal.s2.proxy.config.DatabaseSpec;
import eu.nordtal.s2.proxy.config.GateSpec;
import eu.nordtal.s2.proxy.config.NetworkSpec;
import eu.nordtal.s2.proxy.config.PackSpec;
import eu.nordtal.s2.proxy.db.AccessPool;
import eu.nordtal.s2.proxy.gate.ExpiryWatch;
import eu.nordtal.s2.proxy.gate.FallbackCache;
import eu.nordtal.s2.proxy.gate.GateMessages;
import eu.nordtal.s2.proxy.gate.LoginGate;
import eu.nordtal.s2.proxy.gate.LoginRoster;
import eu.nordtal.s2.proxy.gate.BackendHealth;
import eu.nordtal.s2.proxy.gate.BackendKick;
import eu.nordtal.s2.proxy.gate.MisconfiguredGate;
import eu.nordtal.s2.proxy.gate.RestartGate;
import eu.nordtal.s2.proxy.launch.LaunchCountdown;
import eu.nordtal.s2.proxy.pack.PackMessages;
import eu.nordtal.s2.proxy.pack.PackOffer;
import eu.nordtal.s2.proxy.pack.PackStation;
import eu.nordtal.s2.proxy.pack.WaitingBook;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.network.NetworkCommands;
import eu.nordtal.s2.commands.network.NetworkEffects;
import eu.nordtal.s2.commands.remote.CommandInbox;
import eu.nordtal.s2.common.command.CommandRequests;
import eu.nordtal.s2.common.command.AllowlistDirectory;
import eu.nordtal.s2.common.command.CommandAllowlist;
import eu.nordtal.s2.proxy.command.CommandGate;
import eu.nordtal.s2.proxy.command.InfoTexts;
import eu.nordtal.s2.proxy.command.PrivateMessages;
import eu.nordtal.s2.proxy.command.ProxyNetworkEffects;
import eu.nordtal.s2.proxy.command.VelocityCommands;
import eu.nordtal.s2.common.notify.Channels;
import eu.nordtal.s2.common.notify.NotificationListener;
import eu.nordtal.s2.common.notify.PostgresNotifications;
import eu.nordtal.s2.proxy.phase.PhaseWatch;
import eu.nordtal.s2.proxy.online.OnlineWriter;
import eu.nordtal.s2.proxy.ping.NetworkPing;
import eu.nordtal.s2.proxy.ping.SnapshotStore;
import eu.nordtal.s2.proxy.playtime.PlaytimeStore;
import eu.nordtal.s2.proxy.playtime.PlaytimeWriter;
import eu.nordtal.s2.proxy.routing.PhaseRouting;
import eu.nordtal.s2.proxy.routing.RouteIntents;
import eu.nordtal.s2.proxy.update.Evacuation;
import eu.nordtal.s2.proxy.update.ParkedSeats;
import eu.nordtal.s2.proxy.update.ProxySwap;
import eu.nordtal.s2.proxy.update.Homecoming;
import eu.nordtal.s2.proxy.update.StandbyReturn;
import eu.nordtal.s2.proxy.update.SwapAddresses;
import eu.nordtal.s2.proxy.update.SwapStore;
import eu.nordtal.s2.proxy.update.RestartWatch;

import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wires the season 2 proxy: the login gate, phase routing, play time, the pack station and update handling.
 *
 * A bad {@code database.yml} or {@code gate.yml} fails closed: every login is refused, admins included.
 */
@Plugin(
        id = "proxy",
        name = "proxy",
        version = "${version}",
        description = "Season 2 phase control and backend routing.",
        url = "https://nordtal.eu",
        authors = {"nordtal"}
)
public final class ProxyPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private HikariDataSource pool;
    private AccessDirectory access;
    private NotificationListener phaseListener;

    /** Assigned after the listener starts, which may already call it; volatile, and the poll covers the gap. */
    private volatile RestartWatch restartWatch;
    private volatile Evacuation evacuation;

    /** Commands another process asked this one to run; built after the listener that refers to it. */
    private volatile CommandInbox commandInbox;
    /** {@code :commands}' bundle as the inbox renders it, a second view of the same files. */
    private Messages sharedMessages;
    private PlaytimeWriter playtime;
    private com.velocitypowered.api.scheduler.ScheduledTask heartbeat;

    @Inject
    public ProxyPlugin(final ProxyServer proxy, final Logger logger,
                                @DataDirectory final Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(final ProxyInitializeEvent event) {
        // {server.name} in every message.
        eu.nordtal.s2.common.message.context.Contexts.server("proxy");
        logger.info("proxy enabled, {} backends registered", proxy.getAllServers().size());

        try {
            // Inside the try since Messages.load can throw on a read-only volume; two roots, this module's keys win.
            final Messages messages = Messages.load(getClass().getClassLoader(),
                    List.of("messages/commands", "messages/proxy"),
                    dataDirectory.resolve("messages"), Locale.ENGLISH, Locale.GERMAN);
            messages.unknownOverrideKeys().forEach(key -> logger.warn(
                    "the message override names {}, which no bundle declares - it is stored and"
                            + " never used; check the spelling", key));

            start(Configs.database(dataDirectory, logger).get(),
                    Configs.gate(dataDirectory, logger).get(),
                    Configs.pack(dataDirectory, logger).get(),
                    Configs.network(dataDirectory, logger).get(),
                    Configs.colours(dataDirectory, logger).get(),
                    messages);
        } catch (final ConfigException | RuntimeException failure) {
            failClosed(failure);
        }
    }

    private void start(final DatabaseSpec databaseConfig, final GateSpec gateConfig,
                       final PackSpec packConfig, final NetworkSpec networkConfig,
                       final ColoursSpec coloursConfig, final Messages messages) {
        // :commands' bundle alone, for the inbox: its own keys allow MiniMessage, unlike the layered root.
        this.sharedMessages = Messages.load(getClass().getClassLoader(), "messages/commands",
                dataDirectory.resolve("messages"), Locale.ENGLISH, Locale.GERMAN);
        this.pool = AccessPool.open(databaseConfig);
        this.access = AccessDirectory.using(pool);

        // The five reply colours, read once here; see ColoursSpec.
        final ToneColours colours = ToneColours.parse(Configs.declared(coloursConfig), logger::warn);

        final PhaseDirectory phases = PhaseDirectory.using(pool);
        final GateMessages gateMessages = new GateMessages(messages, gateConfig);
        final FallbackCache fallback = new FallbackCache(Duration.ofMinutes(gateConfig.fallbackCacheWindowMinutes()));
        final LoginRoster roster = new LoginRoster();

        // One PhaseServers for the whole plugin, not one per caller.
        final PhaseServers phaseServers = PhaseServers.from(gateConfig);

        // Both proxies are the same image, so the role comes from configuration.
        final ProxyRole role = ProxyRole.of(networkConfig.standby());
        final PhaseRouting routing = new PhaseRouting(phaseServers, role);
        final java.net.InetSocketAddress publicAddress =
                SwapAddresses.publicAddress(networkConfig.publicAddress()).orElse(null);
        final java.net.InetSocketAddress standbyAddress = publicAddress == null ? null
                : SwapAddresses.standbyAddress(publicAddress, networkConfig.standbyPort())
                        .orElse(null);
        final SwapStore swaps = SwapStore.using(pool);

        // Read once, before Velocity binds its listener, so no login can race it; the statement empties the table.
        ParkedSeats parked;
        try {
            parked = new ParkedSeats(swaps.takeAllSeats(), Clock.systemUTC().instant());
        } catch (final RuntimeException failure) {
            // Not fatal: a seat only changes where an admin lands.
            logger.warn("Could not read where players were standing before the last proxy swap; "
                    + "everybody will be routed by the season phase", failure);
            parked = new ParkedSeats(java.util.List.of(), Clock.systemUTC().instant());
        }
        final ParkedSeats parkedSeats = parked;
        if (parkedSeats.size() > 0) {
            logger.info("Came back from a proxy swap: {} player(s) have a seat waiting",
                    parkedSeats.size());
        }
        final AtomicReference<PlayerRouter> routerRef = new AtomicReference<>();
        final PhaseWatch phaseWatch = new PhaseWatch(phases, logger, (previous, current) -> {
            final PlayerRouter router = routerRef.get();
            if (router != null) {
                router.onPhaseChanged(previous, current);
            }
        });

        // the pack station

        final PackMessages packMessages = new PackMessages(messages);
        final PackOffer offer = packConfig.enabled()
                ? new PackOffer(proxy, packConfig, packMessages)
                : null;
        if (offer == null) {
            logger.warn("pack.yml#enabled is false: NO RESOURCE PACK IS OFFERED. Players still pass "
                    + "through '{}', but every glyph in the tab list, the nametags, the boards and "
                    + "the HUD will render as a missing-glyph box.", phaseServers.limbo());
        } else {
            logger.info("Offering the resource pack from {} (sha1 {}, forced: {})", packConfig.url(),
                    packConfig.sha1(), packConfig.force());
        }

        final WaitingBook book = new WaitingBook(offer != null,
                Duration.ofSeconds(packConfig.applyTimeoutSeconds()),
                Duration.ofSeconds(gateConfig.limboReadyGraceSeconds()), role, Clock.systemUTC());
        // One breaker per backend: BackendKick and the pack station trip it, PlayerRouter clears it on reconnect.
        final BackendHealth backendHealth = new BackendHealth(Clock.systemUTC());
        final PackStation packs = new PackStation(proxy, logger, routing, phaseWatch, roster,
                packMessages, packConfig, offer, book, backendHealth);
        packs.registerChannel();

        // Every destination this plugin chooses is recorded, and every other refused.
        final RouteIntents intents =
                new RouteIntents(roster, phaseServers, logger);
        proxy.getEventManager().register(this, intents);

        // One object for both returns, so each moved player is told once.
        final Homecoming homecoming = new Homecoming(logger, messages, roster, phaseServers);

        final PlayerRouter router = new PlayerRouter(this, proxy, logger, access, routing, phaseWatch,
                roster, fallback, gateMessages, packs, intents, backendHealth,
                parkedSeats, homecoming);
        routerRef.set(router);
        packs.onRelease(router::releaseFromLimbo);
        proxy.getEventManager().register(this, router);
        proxy.getEventManager().register(this, packs);

        final Duration sweepInterval = Duration.ofSeconds(gateConfig.limboSweepIntervalSeconds());
        proxy.getScheduler().buildTask(this, packs::sweep)
                .delay(sweepInterval)
                .repeat(sweepInterval)
                .schedule();

        // Read once, before the first player arrives, so the MAINTENANCE fallback runs as briefly as possible.
        phaseWatch.refresh();

        final Duration pollInterval = Duration.ofSeconds(gateConfig.phasePollIntervalSeconds());

        // The admin roster rides the phase's signals; rerouteAll runs only on a change.
        final Runnable refreshAdmins = () -> {
            final int changed = roster.refreshAdmins(access.admins());
            if (changed > 0) {
                logger.info("The admin flag changed for {} connected player(s); re-routing", changed);
                final PlayerRouter current = routerRef.get();
                if (current != null) {
                    current.rerouteAll(phaseWatch.lastKnown());
                }
            }
        };

        proxy.getScheduler().buildTask(this, () -> {
                    phaseWatch.refresh();
                    refreshAdmins.run();
                })
                .delay(pollInterval)
                .repeat(pollInterval)
                .schedule();

        if (gateConfig.phaseListenEnabled()) {
            // One connection, every refresh on every signal; see eu.nordtal.s2.common.notify.
            this.phaseListener = new NotificationListener(
                    PostgresNotifications.connector(databaseConfig.jdbcUrl(),
                            databaseConfig.username(), databaseConfig.password(),
                            databaseConfig.queryTimeoutSeconds(),
                            "proxy-notification-listener",
                            java.util.List.of(Channels.PHASE, Channels.ADMIN, Channels.COMMAND,
                                    Channels.UPDATE)),
                    "proxy-phase-listener",
                    java.util.List.of(
                            new NotificationListener.Refresh("the season phase", phaseWatch::refresh),
                            new NotificationListener.Refresh("the admin roster", refreshAdmins),
                            new NotificationListener.Refresh("the command inbox", () -> {
                                // Null until the command layer is built further down; the poll covers that window.
                                final CommandInbox inbox = commandInbox;
                                if (inbox != null) {
                                    inbox.drain();
                                }
                            }),
                            // Latency here would drop the 30 second beat; null until built, like the inbox.
                            new NotificationListener.Refresh("the restart countdown", () -> {
                                final RestartWatch watch = restartWatch;
                                if (watch != null) {
                                    watch.check();
                                }
                            }),
                            // The evacuation rides the same signal.
                            new NotificationListener.Refresh("the update evacuation", () -> {
                                final Evacuation moving = evacuation;
                                if (moving != null) {
                                    moving.check();
                                }
                            })),
                    logger, pollInterval);
            phaseListener.start();
        } else {
            logger.info("The {} and {} LISTEN connection is disabled; the {}s poll is the only path "
                    + "a phase switch or an admin change travels",
                    Channels.PHASE, Channels.ADMIN, pollInterval.toSeconds());
        }

        // the gate

        final LoginGate loginGate = new LoginGate(logger, proxy, access, fallback, roster, gateMessages,
                gateConfig, networkConfig, Clock.systemUTC());
        final ExpiryWatch expiryWatch = new ExpiryWatch(proxy, logger, access, fallback, gateMessages,
                Duration.ofMinutes(gateConfig.expiryWarningLeadMinutes()));

        proxy.getEventManager().register(this, loginGate);
        proxy.getEventManager().register(this, roster);
        proxy.getEventManager().register(this, expiryWatch);
        // A kick with a reason keeps the backend's screen; one with none goes to the waiting room. See BackendKick.
        proxy.getEventManager().register(this, new BackendKick(proxy, phaseServers,
                backendHealth, gateMessages, roster, logger));

        proxy.getScheduler().buildTask(this, expiryWatch::check)
                .delay(Duration.ofSeconds(gateConfig.expiryCheckIntervalSeconds()))
                .repeat(Duration.ofSeconds(gateConfig.expiryCheckIntervalSeconds()))
                .schedule();

        // The MOTD and limit, out of network.yml; refreshed on a timer, never on the unauthenticated ping itself.
        final SnapshotStore snapshots = SnapshotStore.using(pool, logger);
        final Duration snapshotInterval = Duration.ofSeconds(networkConfig.snapshotRefreshSeconds());
        snapshots.refresh();
        proxy.getScheduler().buildTask(this, snapshots::refresh)
                .delay(snapshotInterval)
                .repeat(snapshotInterval)
                .schedule();
        proxy.getEventManager().register(this, new NetworkPing(proxy, logger, networkConfig, phaseWatch,
                snapshots, messages, Clock.systemUTC(),
                eu.nordtal.s2.proxy.ping.ServerIcon.load(dataDirectory, logger)));

        // This proxy knows every connection, so it writes the counts and who is connected.
        final OnlineWriter onlineWriter = new OnlineWriter(proxy, phaseServers,
                OnlineDirectory.using(pool), OnlineRoster.using(pool), role, logger);
        onlineWriter.write();
        proxy.getScheduler().buildTask(this, onlineWriter::tick)
                .delay(OnlineWriter.TICK)
                .repeat(OnlineWriter.TICK)
                .schedule();

        // play time

        this.playtime = new PlaytimeWriter(PlaytimeStore.using(pool), roster, logger);
        proxy.getEventManager().register(this, playtime);

        final Duration flushInterval = Duration.ofSeconds(gateConfig.playtimeFlushIntervalSeconds());
        proxy.getScheduler().buildTask(this, playtime::flushAll)
                .delay(flushInterval)
                .repeat(flushInterval)
                .schedule();

        // Only the proxy sees every player, so it gives the warning, counting towards the row's instant.
        this.restartWatch = new RestartWatch(this, proxy, logger,
                UpdateDirectory.using(pool), roster, messages, phaseServers, Clock.systemUTC());
        proxy.getScheduler().buildTask(this, this.restartWatch::check)
                .delay(RestartWatch.INTERVAL)
                .repeat(RestartWatch.INTERVAL)
                .schedule();

        // Moves players into the waiting room, on its own task beside the countdown.
        this.evacuation = new Evacuation(proxy, logger,
                UpdateDirectory.using(pool), phaseServers, homecoming);
        packs.whenUpdating(this.evacuation::isMoving);
        packs.whenHeld(this.evacuation::isHeld);
        // The counts hurry from the countdown on, not from the move; see OnlineWriter#tick.
        onlineWriter.whenHurrying(() ->
                this.restartWatch.isCountingDown() || this.evacuation.isAnyMoving());
        // The countdown already schedules a task on the zero instant; the evacuation rides it.
        proxy.getScheduler().buildTask(this, this.evacuation::check)
                .delay(RestartWatch.INTERVAL)
                .repeat(RestartWatch.INTERVAL)
                .schedule();

        // Parks the network when this proxy itself stops; `role` decides which half acts.
        final ProxySwap swap = new ProxySwap(proxy, logger, UpdateDirectory.using(pool), swaps,
                role, standbyAddress, Clock.systemUTC());
        // Second at zero: backends first, then the network, the order a player travels.
        this.restartWatch.whenZeroReached(() -> {
            this.evacuation.check();
            swap.check();
        });
        // Lets the announcement say whether a standby catches players, once per countdown.
        this.restartWatch.standbyProxyAnswers(swap::canPark);
        proxy.getScheduler().buildTask(this, swap::check)
                .delay(RestartWatch.INTERVAL)
                .repeat(RestartWatch.INTERVAL)
                .schedule();

        // An arrival between zero and the stop gets a sentence and goes to the standby too.
        proxy.getEventManager().register(this,
                new RestartGate(logger, swap::isStopping, parkedSeats::holds, swap::park,
                        gateMessages, fallback));

        final StandbyReturn standbyReturn = new StandbyReturn(this, proxy, logger, swaps, role,
                publicAddress, Clock.systemUTC(), homecoming);
        proxy.getScheduler().buildTask(this, standbyReturn::check)
                .delay(StandbyReturn.INTERVAL)
                .repeat(StandbyReturn.INTERVAL)
                .schedule();

        // Logged at start: a silent swap failure would look like a network that just went down.
        if (role.isStandby()) {
            logger.info("THIS IS THE STANDBY PROXY. Arrivals are held in '{}' and transferred back "
                            + "to {} as soon as it answers again; no player counts are written "
                            + "from here.", phaseServers.limboStandby(),
                    publicAddress == null ? "nowhere - network.yml#public-address is empty"
                            : publicAddress.getHostString() + ":" + publicAddress.getPort());
        } else if (swap.isArmed()) {
            logger.info("An update that moves this proxy will park everybody on {}:{} instead of "
                            + "disconnecting them", standbyAddress.getHostString(),
                    standbyAddress.getPort());
        } else {
            logger.warn("network.yml#public-address is empty or carries no port, so an update that "
                    + "moves this proxy will DISCONNECT every connected player. That is the old "
                    + "behaviour and a valid choice; set it to the host:port players type to swap "
                    + "proxies instead.");
        }

        // One list, in network.yml, enforced here and published for the Paper servers. See CommandGate/CommandFilter.
        final CommandAllowlist allowlist =
                CommandAllowlist.parse(networkConfig.commandAllowlist());
        proxy.getEventManager().register(this,
                new CommandGate(roster, allowlist, messages, logger, () -> colours,
                        eu.nordtal.s2.proxy.feedback.ProxySounds.defaults(logger::warn)));
        if (allowlist.entries().isEmpty()) {
            logger.warn("network.yml#command-allowlist is empty: a player who is not an admin can "
                    + "type no command at all, anywhere on this network. That is a valid setting "
                    + "and almost certainly not the one that was meant.");
        } else {
            logger.info("Players who are not admins may use: {}", allowlist);
        }
        try {
            if (AllowlistDirectory.using(pool).publish(allowlist)) {
                logger.info("Published the command allowlist for the three Paper backends");
            }
        } catch (final RuntimeException failure) {
            // Not fatal: this proxy's enforcement reads the file, not the row; only the tab-completion filter is lost.
            logger.warn("Could not publish the command allowlist; the Paper backends will keep "
                    + "whatever list they last read. This proxy still enforces it.", failure);
        }

        // Every decision lives in :commands; the proxy registers only its own commands here.
        final NetworkEffects networkEffects = new ProxyNetworkEffects(
                ProxyNetworkEffects.async(this, proxy), messages, sharedMessages, logger);

        final VelocityCommands tree = new VelocityCommands(proxy, roster, messages, () -> colours);
        NetworkCommands.all().forEach(command -> tree.local(command, networkEffects));

        // The five a player types, as plain Velocity Brigadier outside `tree`, not admin-only.
        final PrivateMessages privateMessages =
                new PrivateMessages(proxy, roster, messages, () -> colours, logger);
        // Also a listener: it tracks who last spoke to whom for /r, dropped when somebody leaves.
        proxy.getEventManager().register(this, privateMessages);

        // The invite is gate.yml's, the same string every login screen already uses.
        final InfoTexts infoTexts =
                new InfoTexts(messages, gateConfig.discordInviteUrl(), roster);

        final CommandManager commands = proxy.getCommandManager();
        final List<com.velocitypowered.api.command.BrigadierCommand> registered =
                new java.util.ArrayList<>(tree.build());
        registered.addAll(privateMessages.commands());
        registered.addAll(infoTexts.commands());
        registered.forEach(command -> commands.register(
                commands.metaBuilder(command).plugin(this).build(), command));

        // The proxy's own inbox: /network reload arrives as a request row.
        commandInbox = new CommandInbox(Target.PROXY,
                CommandRequests.borrowing(pool),
                // :commands' bundle alone; the layered one allows MiniMessage an admin would read literally.
                sharedMessages,
                eu.nordtal.s2.commands.remote.CommandInbox.AdminCheck.of(
                        access::admins, access::adminMinecraftAccounts),
                (message, failure) -> logger.warn(message, failure));
        NetworkCommands.all().forEach(command -> commandInbox.register(command,
                // Inline: the inbox settles a request row when the command returns, before any scheduled effect.
                new ProxyNetworkEffects(Runnable::run, messages, sharedMessages, logger)));
        proxy.getScheduler().buildTask(this, commandInbox::drain)
                .delay(java.time.Duration.ofSeconds(5))
                .repeat(java.time.Duration.ofSeconds(5))
                .schedule();

        logger.info("Access login gate is up in phase {} (query timeout {}s, fallback cache window "
                        + "{}m, expiry check every {}s, phase poll every {}s, play time flushed every "
                        + "{}s, waiting room '{}' swept every {}s)",
                phaseWatch.lastKnown(), databaseConfig.queryTimeoutSeconds(),
                gateConfig.fallbackCacheWindowMinutes(), gateConfig.expiryCheckIntervalSeconds(),
                pollInterval.toSeconds(), flushInterval.toSeconds(), phaseServers.limbo(),
                sweepInterval.toSeconds());
        logger.info("The network takes {} players, the browser is told so, and every Paper backend "
                        + "is set to the same number. MOTD refreshed every {}s.",
                networkConfig.maxPlayers(), snapshotInterval.toSeconds());
        if (phaseWatch.lastKnown() == SeasonPhase.PRE_LAUNCH) {
            logger.info("The network has not opened yet: only admins get in, everybody else is shown "
                            + "the countdown ({}).",
                    LaunchCountdown.render(messages, Locale.ENGLISH, phaseWatch.launch().orElse(null),
                            Clock.systemUTC().instant()));
        }

        startHeartbeat();
    }

    /**
     * Starts the container readiness marker (see {@link Readiness}) as the last step of {@link #start}.
     *
     * {@link #failClosed} never calls it, so a proxy refusing every login never reports ready.
     */
    private void startHeartbeat() {
        final Readiness readiness = Readiness.onDefaultPath(logger::warn);
        heartbeat = proxy.getScheduler().buildTask(this, readiness::refresh)
                .delay(Duration.ZERO)
                .repeat(Readiness.BEAT)
                .schedule();
    }

    /** Registers the only login handler, which refuses everybody. */
    private void failClosed(final Exception failure) {
        logger.error("proxy could not start, so NOBODY will be let onto this network. "
                + "Fix the configuration and restart the proxy.");
        logger.error("{}", failure.getMessage(), failure);

        // Its own bundle, from the classpath with NO override directory: that layer is one thing that can break.
        try {
            final Messages messages = Messages.load(getClass().getClassLoader(),
                    "messages/proxy", Locale.ENGLISH, Locale.GERMAN);
            proxy.getEventManager().register(this, new MisconfiguredGate(logger, messages));
        } catch (final RuntimeException broken) {
            // The packaged bundle is inside the jar; reaching here means it is damaged, so the proxy shuts down.
            logger.error("proxy cannot even load its own packaged messages, so it cannot "
                    + "put up a refusal screen. Stopping the proxy - that is the only way left to "
                    + "refuse everybody.", broken);
            closeResources();
            proxy.shutdown();
            return;
        }

        // Whatever got opened before the failure has to go: a half-built plugin holding a pool is worse than nothing.
        closeResources();
    }

    @Subscribe
    public void onProxyShutdown(final ProxyShutdownEvent event) {
        if (playtime != null) {
            // The last slice of every session, so a planned restart costs nobody play time.
            logger.info("Flushed play time for {} players on shutdown", playtime.flushAll());
        }
        closeResources();
    }

    private void closeResources() {
        // Stops the beat so a proxy going down stops claiming to be up; going stale, not deleted, is the signal.
        if (heartbeat != null) {
            heartbeat.cancel();
            heartbeat = null;
        }
        if (phaseListener != null) {
            phaseListener.close();
            phaseListener = null;
        }
        // access.close() never owns the pool; this proxy built it with AccessPool and closes it.
        if (pool != null) {
            pool.close();
            pool = null;
        }
        access = null;
    }
}
