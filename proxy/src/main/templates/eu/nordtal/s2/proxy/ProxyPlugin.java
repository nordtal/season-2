package eu.nordtal.s2.proxy;

import eu.nordtal.s2.common.time.NetworkTime;

import eu.nordtal.s2.common.language.Languages;

import eu.nordtal.s2.messages.context.MessageEnvironment;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;

import com.zaxxer.hikari.HikariDataSource;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.database.access.AccessDirectory;
import eu.nordtal.s2.common.health.Readiness;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messagerendering.ToneColours;
import eu.nordtal.s2.database.online.OnlineDirectory;
import eu.nordtal.s2.database.online.OnlineRoster;
import eu.nordtal.s2.database.phase.PhaseDirectory;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.proxy.config.ProxySettings;
import eu.nordtal.s2.settings.Colours;
import eu.nordtal.s2.settings.ColoursSpec;
import eu.nordtal.s2.settings.DatabasePool;
import eu.nordtal.s2.settings.DatabaseSpec;
import eu.nordtal.s2.settings.FileSettings;
import eu.nordtal.s2.settings.Settings;
import eu.nordtal.s2.settings.SettingsException;
import eu.nordtal.s2.proxy.config.GateSpec;
import eu.nordtal.s2.proxy.config.NetworkSpec;
import eu.nordtal.s2.proxy.config.PackSpec;
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
import eu.nordtal.s2.commands.network.NetworkCommands;
import eu.nordtal.s2.commands.network.NetworkEffects;
import eu.nordtal.s2.database.command.AllowlistDirectory;
import eu.nordtal.s2.database.command.CommandAllowlist;
import eu.nordtal.s2.proxy.command.CommandGate;
import eu.nordtal.s2.proxy.command.InfoTexts;
import eu.nordtal.s2.proxy.command.PrivateMessages;
import eu.nordtal.s2.proxy.command.ProxyNetworkEffects;
import eu.nordtal.s2.proxy.command.VelocityCommands;
import eu.nordtal.s2.database.notify.Channel;
import eu.nordtal.s2.database.notify.SignalHub;
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

    /** The process every message of this proxy renders for. */
    private static final MessageEnvironment ENVIRONMENT = MessageEnvironment.of("proxy");

    /** The one clock of this process. */
    private final Clock clock = NetworkTime.clock();

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private HikariDataSource pool;
    private AccessDirectory access;
    private SignalHub signals;

    /** Built before the signal hub starts; volatile, since the hub's thread reads them. */
    private volatile RestartWatch restartWatch;
    private volatile Evacuation evacuation;

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
        logger.info("proxy enabled, {} backends registered", proxy.getAllServers().size());

        try {
            // Inside the try since Messages.load can throw on a read-only volume; two roots, this module's keys win.
            final Messages messages = Messages.load(getClass().getClassLoader(),
                    List.of("messages/commands", "messages/proxy"),
                    dataDirectory.resolve("messages"), Languages.NETWORK.locales()).within(ENVIRONMENT);
            messages.unknownOverrideKeys().forEach(key -> logger.warn(
                    "the message override names {}, which no bundle declares - it is stored and"
                            + " never used; check the spelling", key));

            final Settings settings = FileSettings.in(dataDirectory, "NORDTAL_PROXY", "proxy", logger);
            start(settings.load("database", DatabaseSpec.class, DatabasePool::check).get(),
                    settings.load("gate", GateSpec.class, ProxySettings::checkGate).get(),
                    settings.load("pack", PackSpec.class, ProxySettings::checkPack).get(),
                    settings.load("network", NetworkSpec.class, ProxySettings::checkNetwork).get(),
                    settings.load("colours", ColoursSpec.class).get(),
                    messages);
        } catch (final SettingsException | RuntimeException failure) {
            failClosed(failure);
        }
    }

    private void start(final DatabaseSpec databaseConfig, final GateSpec gateConfig,
                       final PackSpec packConfig, final NetworkSpec networkConfig,
                       final ColoursSpec coloursConfig, final Messages messages) {
        // :commands' bundle alone, for the inbox: its own keys allow MiniMessage, unlike the layered root.
        this.sharedMessages = Messages.load(getClass().getClassLoader(), "messages/commands",
                dataDirectory.resolve("messages"), Languages.NETWORK.locales()).within(ENVIRONMENT);
        this.pool = DatabasePool.open(databaseConfig, "proxy-access");
        this.access = AccessDirectory.using(pool, clock);

        // The five reply colours, read once here; see ColoursSpec.
        final ToneColours colours = ToneColours.parse(Colours.declared(coloursConfig), logger::warn);

        final PhaseDirectory phases = PhaseDirectory.using(pool, clock);
        final GateMessages gateMessages = new GateMessages(messages, gateConfig);
        final FallbackCache fallback = new FallbackCache(Duration.ofMinutes(gateConfig.fallbackCacheWindowMinutes()), clock);
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
            parked = new ParkedSeats(swaps.takeAllSeats(), clock.instant());
        } catch (final RuntimeException failure) {
            // Not fatal: a seat only changes where an admin lands.
            logger.warn("Could not read where players were standing before the last proxy swap; "
                    + "everybody will be routed by the season phase", failure);
            parked = new ParkedSeats(java.util.List.of(), clock.instant());
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
                Duration.ofSeconds(gateConfig.limboReadyGraceSeconds()), role, clock);
        // One breaker per backend: BackendKick and the pack station trip it, PlayerRouter clears it on reconnect.
        final BackendHealth backendHealth = new BackendHealth(clock);
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
                parkedSeats, homecoming, clock);
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

        // One LISTEN connection for the whole proxy, started last; every refresh runs on connect and every signal.
        this.signals = SignalHub.open(databaseConfig.jdbcUrl(), databaseConfig.username(),
                databaseConfig.password(), databaseConfig.queryTimeoutSeconds(), "proxy-signals", logger);
        signals.on(Channel.PHASE, "the season phase", phaseWatch::refresh);
        signals.on(Channel.ADMIN, "the admin roster", refreshAdmins);
        // Latency here would drop the 30 second beat.
        signals.on(Channel.UPDATE, "the restart countdown", () -> {
            final RestartWatch watch = restartWatch;
            if (watch != null) {
                watch.check();
            }
        });
        signals.on(Channel.UPDATE, "the update evacuation", () -> {
            final Evacuation moving = evacuation;
            if (moving != null) {
                moving.check();
            }
        });

        // the gate

        final LoginGate loginGate = new LoginGate(logger, proxy, access, fallback, roster, gateMessages,
                gateConfig, networkConfig, clock);
        final ExpiryWatch expiryWatch = new ExpiryWatch(proxy, logger, access, fallback, gateMessages,
                Duration.ofMinutes(gateConfig.expiryWarningLeadMinutes()), clock);

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
                snapshots, messages, clock,
                eu.nordtal.s2.proxy.ping.ServerIcon.load(dataDirectory, logger)));

        // This proxy knows every connection, so it writes the counts and who is connected.
        final OnlineWriter onlineWriter = new OnlineWriter(proxy, phaseServers,
                OnlineDirectory.using(pool, clock), OnlineRoster.using(pool, clock), role, logger, clock);
        onlineWriter.write();
        proxy.getScheduler().buildTask(this, onlineWriter::tick)
                .delay(OnlineWriter.TICK)
                .repeat(OnlineWriter.TICK)
                .schedule();

        // play time

        this.playtime = new PlaytimeWriter(PlaytimeStore.using(pool), roster, logger, clock);
        proxy.getEventManager().register(this, playtime);

        final Duration flushInterval = Duration.ofSeconds(gateConfig.playtimeFlushIntervalSeconds());
        proxy.getScheduler().buildTask(this, playtime::flushAll)
                .delay(flushInterval)
                .repeat(flushInterval)
                .schedule();

        // Only the proxy sees every player, so it gives the warning, counting towards the row's instant.
        this.restartWatch = new RestartWatch(this, proxy, logger,
                UpdateDirectory.using(pool), roster, messages, phaseServers, clock);
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
                role, standbyAddress, clock);
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
                publicAddress, clock, homecoming);
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

        logger.info("Access login gate is up in phase {} (query timeout {}s, fallback cache window "
                        + "{}m, expiry check every {}s, play time flushed every "
                        + "{}s, waiting room '{}' swept every {}s)",
                phaseWatch.lastKnown(), databaseConfig.queryTimeoutSeconds(),
                gateConfig.fallbackCacheWindowMinutes(), gateConfig.expiryCheckIntervalSeconds(),
                flushInterval.toSeconds(), phaseServers.limbo(),
                sweepInterval.toSeconds());
        logger.info("The network takes {} players, the browser is told so, and every Paper backend "
                        + "is set to the same number. MOTD refreshed every {}s.",
                networkConfig.maxPlayers(), snapshotInterval.toSeconds());
        if (phaseWatch.lastKnown() == SeasonPhase.PRE_LAUNCH) {
            logger.info("The network has not opened yet: only admins get in, everybody else is shown "
                            + "the countdown ({}).",
                    LaunchCountdown.render(messages, Locale.ENGLISH, phaseWatch.launch().orElse(null),
                            clock.instant()));
        }

        signals.start();
        startHeartbeat();
    }

    /**
     * Starts the container readiness marker (see {@link Readiness}) as the last step of {@link #start}.
     *
     * {@link #failClosed} never calls it, so a proxy refusing every login never reports ready.
     */
    private void startHeartbeat() {
        final Readiness readiness = Readiness.onDefaultPath(clock, logger::warn);
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
                    "messages/proxy", Languages.NETWORK.locales()).within(ENVIRONMENT);
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
        if (signals != null) {
            signals.close();
            signals = null;
        }
        if (pool != null) {
            pool.close();
            pool = null;
        }
        access = null;
    }
}
