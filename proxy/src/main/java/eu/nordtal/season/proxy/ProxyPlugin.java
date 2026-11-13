package eu.nordtal.season.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.zaxxer.hikari.HikariDataSource;
import eu.nordtal.season.common.health.Readiness;
import eu.nordtal.season.common.language.Languages;
import eu.nordtal.season.common.season.SeasonOpening;
import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.common.time.NetworkTime;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.access.AccessDirectory;
import eu.nordtal.season.database.access.Prestige;
import eu.nordtal.season.database.command.CommandTreeStore;
import eu.nordtal.season.database.command.CommandTreeWriter;
import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.database.document.DocumentStore;
import eu.nordtal.season.database.message.MessageOverrideStore;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.database.online.OnlineDirectory;
import eu.nordtal.season.database.online.OnlineRoster;
import eu.nordtal.season.database.season.SeasonDirectory;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messagerendering.Names;
import eu.nordtal.season.messagerendering.ToneColours;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.proxy.command.CommandGate;
import eu.nordtal.season.proxy.command.CommandTrees;
import eu.nordtal.season.proxy.command.InfoTexts;
import eu.nordtal.season.proxy.command.PrivateMessages;
import eu.nordtal.season.proxy.config.GateSpec;
import eu.nordtal.season.proxy.config.NetworkSpec;
import eu.nordtal.season.proxy.config.PackSpec;
import eu.nordtal.season.proxy.config.ProxySettings;
import eu.nordtal.season.proxy.config.QueueSpec;
import eu.nordtal.season.proxy.feedback.ProxySounds;
import eu.nordtal.season.proxy.gate.BackendHealth;
import eu.nordtal.season.proxy.gate.BackendKick;
import eu.nordtal.season.proxy.gate.ExpiryWatch;
import eu.nordtal.season.proxy.gate.FallbackCache;
import eu.nordtal.season.proxy.gate.GateMessages;
import eu.nordtal.season.proxy.gate.LoginGate;
import eu.nordtal.season.proxy.gate.LoginRoster;
import eu.nordtal.season.proxy.gate.MisconfiguredGate;
import eu.nordtal.season.proxy.gate.RestartGate;
import eu.nordtal.season.proxy.gate.WaitingRooms;
import eu.nordtal.season.proxy.launch.LaunchCountdown;
import eu.nordtal.season.proxy.online.OnlineWriter;
import eu.nordtal.season.proxy.pack.AccessQueue;
import eu.nordtal.season.proxy.pack.PackMessages;
import eu.nordtal.season.proxy.pack.PackStation;
import eu.nordtal.season.proxy.pack.WaitingBook;
import eu.nordtal.season.proxy.ping.NetworkPing;
import eu.nordtal.season.proxy.ping.ServerIcon;
import eu.nordtal.season.proxy.ping.SnapshotStore;
import eu.nordtal.season.proxy.playtime.PlaytimeStore;
import eu.nordtal.season.proxy.playtime.PlaytimeWriter;
import eu.nordtal.season.proxy.routing.RouteIntents;
import eu.nordtal.season.proxy.routing.SeasonRouting;
import eu.nordtal.season.proxy.season.SeasonWatch;
import eu.nordtal.season.proxy.time.VelocityScheduler;
import eu.nordtal.season.proxy.update.Evacuation;
import eu.nordtal.season.proxy.update.Homecoming;
import eu.nordtal.season.proxy.update.ParkedSeats;
import eu.nordtal.season.proxy.update.ProxySwap;
import eu.nordtal.season.proxy.update.RestartWatch;
import eu.nordtal.season.proxy.update.StandbyReturn;
import eu.nordtal.season.proxy.update.SwapAddresses;
import eu.nordtal.season.proxy.update.SwapStore;
import eu.nordtal.season.settings.Colours;
import eu.nordtal.season.settings.DatabasePool;
import eu.nordtal.season.settings.DatabaseSettings;
import eu.nordtal.season.settings.DatabaseSpec;
import eu.nordtal.season.settings.Environment;
import eu.nordtal.season.settings.EnvironmentSettings;
import eu.nordtal.season.settings.Group;
import eu.nordtal.season.settings.Setting;
import eu.nordtal.season.settings.SettingsException;
import eu.nordtal.season.settings.network.LanguageAndTimeSpec;
import eu.nordtal.season.settings.network.NetworkSettings;
import eu.nordtal.season.settings.network.PlayersSpec;
import eu.nordtal.season.settings.network.PrestigeSpec;
import eu.nordtal.season.settings.network.SeasonSpec;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Creates the features of the proxy.
 * An invalid {@code database} or {@code gate} group makes the proxy refuse every login, admins included.
 */
@Plugin(
        id = "proxy",
        name = "proxy",
        version = ProxyVersion.VALUE,
        description = "Season 2 season gate and backend routing.",
        url = "https://nordtal.eu",
        authors = {"nordtal"})
public final class ProxyPlugin {

    /** How often a waiting queue onto {@code smp} or a running update is swept. */
    private static final Duration BUSY_TICK = Duration.ofSeconds(1);

    /** The clock. */
    private final Clock clock = NetworkTime.clock();

    /** The languages of the network, the defaults until the settings are read. */
    private Languages languages = NetworkSettings.defaultLanguages();

    /** The message bundle, set after the settings. */
    private @Nullable Messages messages;

    /** The crests of the prestige group. */
    private volatile Prestige prestige = Prestige.defaults();

    private final ProxyServer proxy;
    private final Logger logger;

    /** The scheduler. */
    private final VelocityScheduler scheduler;

    private final Path dataDirectory;

    private @Nullable HikariDataSource pool;

    /** The packaged documents with the admins' edits over them. */
    private final AppliedDocuments documents = new AppliedDocuments(getClass().getClassLoader(), Set.of("proxy"));

    private @Nullable SignalHub signals;
    private @Nullable SignalHub numberSignals;
    private @Nullable PlaytimeWriter playtime;
    private Scheduler.@Nullable Task heartbeat;

    /** The settings groups and the pool. */
    private record Loaded(
            HikariDataSource pool,
            DatabaseSpec database,
            GateSpec gate,
            Setting<PackSpec> pack,
            NetworkSpec network,
            Messages messages,
            DatabaseSettings settings,
            Setting<PlayersSpec> players,
            Setting<PrestigeSpec> prestigeSetting,
            Setting<QueueSpec> queue) {}

    /** The objects that several features use. */
    private record Shared(
            Loaded loaded,
            AccessDirectory access,
            LoginRoster roster,
            MessageRenderer renderer,
            GateMessages gateMessages,
            FallbackCache fallback,
            NetworkServers servers,
            ProxyRole role,
            SeasonRouting routing,
            SeasonWatch seasonWatch,
            ParkedSeats parkedSeats,
            SwapStore swaps,
            @Nullable InetSocketAddress publicAddress,
            @Nullable InetSocketAddress standbyAddress,
            BackendHealth backendHealth,
            Homecoming homecoming,
            AtomicReference<PlayerRouter> routerRef) {}

    /** The two update tasks that the signal hub triggers. */
    private record Updates(RestartWatch restartWatch, Evacuation evacuation) {}

    /** The two further checks that the signal hub triggers. */
    private record Reads(ProxySwap swap, ExpiryWatch expiry) {}

    @Inject
    public ProxyPlugin(final ProxyServer proxy, final Logger logger, @DataDirectory final Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.scheduler = VelocityScheduler.of(proxy, this);
    }

    @Subscribe
    public void onProxyInitialize(final ProxyInitializeEvent event) {
        logger.info(
                "proxy enabled, backends registered: count={}",
                proxy.getAllServers().size());
        try {
            start(load());
        } catch (final SettingsException | RuntimeException failure) {
            failClosed(failure);
        }
    }

    /** Opens the pool and reads the settings groups. */
    private Loaded load() throws SettingsException {
        final Environment environment = Environment.of("NORDTAL_PROXY").withMain("proxy");
        final DatabaseSpec database = EnvironmentSettings.of(environment)
                .load(Group.of("database", DatabaseSpec.class).checkedBy(DatabasePool::check))
                .get();
        final HikariDataSource opened = DatabasePool.open(database, "proxy-access");
        this.pool = opened;
        final DatabaseSettings settings =
                DatabaseSettings.over(SettingStore.using(opened), "proxy", environment, logger);
        final GateSpec gate = settings.load(Group.of("gate", GateSpec.class)).get();
        final Setting<PackSpec> pack = settings.load(Group.of("pack", PackSpec.class)
                .checkedBy(ProxySettings::checkPack)
                .whileRunning());
        final NetworkSpec network = settings.load(
                        Group.of("network", NetworkSpec.class).whileRunning())
                .get();
        final Setting<QueueSpec> queue =
                settings.load(Group.of("queue", QueueSpec.class).whileRunning());
        final ToneColours colours =
                ToneColours.parse(Colours.declared(settings.load(Colours.GROUP).get()), logger::warn);
        final Setting<PlayersSpec> players = settings.load(NetworkSettings.PLAYERS);
        final Setting<PrestigeSpec> prestigeSetting = settings.load(NetworkSettings.PRESTIGE);
        prestige = NetworkSettings.prestige(prestigeSetting.get());
        final SeasonSpec season = settings.load(NetworkSettings.SEASON).get();
        final LanguageAndTimeSpec languageAndTime =
                settings.load(NetworkSettings.LANGUAGE_AND_TIME).get();
        languages = NetworkSettings.languages(languageAndTime);
        final Messages loadedMessages = Messages.load(
                        getClass().getClassLoader(), List.of("messages/database"), languages.locales())
                .within(NetworkSettings.environment("proxy", season, languageAndTime, colours));
        this.messages = loadedMessages;
        return new Loaded(
                opened, database, gate, pack, network, loadedMessages, settings, players, prestigeSetting, queue);
    }

    /** Creates every feature, registers the event handlers in order, then starts the signal hub and the heartbeat. */
    private void start(final Loaded loaded) {
        final Shared shared = shared(loaded);
        final PackStation packs = wirePackStation(shared);
        final PlayerRouter router = wireRouting(shared, packs);
        final ExpiryWatch expiry = wireLoginGate(shared);
        final SnapshotStore snapshots = wireServerList(shared);
        final OnlineWriter onlineWriter = wireOnlineCounts(shared);
        wirePlaytime(shared);
        final Updates updates = wireUpdates(shared, packs, onlineWriter);
        final ProxySwap swap = wireProxySwap(shared, updates);
        wireCommands(shared);
        final SignalHub hub = wireSignals(shared, router, updates, new Reads(swap, expiry));
        final SignalHub numbers = wireNumbers(shared, snapshots);
        logStartup(shared);
        hub.start();
        numbers.start();
        startHeartbeat();
        holdUntilWaitingRoom(shared.servers());
    }

    /**
     * Holds the init event until a waiting room answers.
     * Velocity binds the port only after it, so the standby's probe, the healthcheck and a run's check see the proxy
     * as up only once it can place a player.
     */
    private void holdUntilWaitingRoom(final NetworkServers servers) {
        logger.info("port opening held: waiting_room={} standby_room={}", servers.limbo(), servers.limboStandby());
        if (WaitingRooms.answering(proxy, servers, Waiting.on(clock), WaitingRooms.AT_START)
                .isEmpty()) {
            logger.warn(
                    "no waiting room answered, port opened: waiting_room={} standby_room={} seconds={}",
                    servers.limbo(),
                    servers.limboStandby(),
                    WaitingRooms.AT_START.toSeconds());
        }
    }

    /** Creates the objects that several features use. */
    private Shared shared(final Loaded loaded) {
        final HikariDataSource pool = loaded.pool();
        final AccessDirectory access = AccessDirectory.using(pool, clock);
        final LoginRoster roster = new LoginRoster();
        final MessageRenderer renderer = MessageRenderer.of(loaded.messages(), Names.BARE, roster.cards(() -> prestige))
                .reading(documents);
        final NetworkServers servers = NetworkServers.from(loaded.gate());
        final ProxyRole role = ProxyRole.of(loaded.network().standby());
        final InetSocketAddress publicAddress =
                SwapAddresses.publicAddress(loaded.network().publicAddress()).orElse(null);
        final InetSocketAddress standbyAddress = publicAddress == null
                ? null
                : SwapAddresses.standbyAddress(publicAddress, loaded.network().standbyPort())
                        .orElse(null);
        final SwapStore swaps = SwapStore.using(pool);
        final AtomicReference<PlayerRouter> routerRef = new AtomicReference<>();
        final SeasonWatch seasonWatch =
                new SeasonWatch(SeasonDirectory.using(pool, clock), scheduler, clock, logger, (previous, current) -> {
                    final PlayerRouter router = routerRef.get();
                    if (router != null) {
                        router.onSeasonChanged(previous, current);
                    }
                });
        return new Shared(
                loaded,
                access,
                roster,
                renderer,
                new GateMessages(renderer, loaded.gate()),
                new FallbackCache(Duration.ofMinutes(loaded.gate().fallbackCacheWindowMinutes()), clock),
                servers,
                role,
                new SeasonRouting(servers, role),
                seasonWatch,
                takeParkedSeats(swaps),
                swaps,
                publicAddress,
                standbyAddress,
                new BackendHealth(clock),
                new Homecoming(
                        logger,
                        renderer,
                        roster,
                        servers,
                        UpdateDirectory.using(pool).recorder()),
                routerRef);
    }

    /** Reads the servers of the players before the last proxy swap. */
    private ParkedSeats takeParkedSeats(final SwapStore swaps) {
        final ParkedSeats parked;
        try {
            parked = new ParkedSeats(swaps.takeAllSeats(), clock.instant());
        } catch (final RuntimeException failure) {
            logger.warn("swap seats unreadable, players routed by season", failure);
            return new ParkedSeats(List.of(), clock.instant());
        }
        if (parked.size() > 0) {
            logger.info("swap seats found: count={}", parked.size());
        }
        return parked;
    }

    /** Creates the pack station. */
    private PackStation wirePackStation(final Shared shared) {
        final Setting<PackSpec> config = shared.loaded().pack();
        final PackMessages packMessages = new PackMessages(shared.renderer());
        logPack(config.get(), "resource pack configured");
        final WaitingBook book = new WaitingBook(
                () -> config.get().enabled(),
                () -> Duration.ofSeconds(config.get().applyTimeoutSeconds()),
                Duration.ofSeconds(shared.loaded().gate().limboReadyGraceSeconds()),
                shared.role(),
                new AccessQueue(
                        shared.servers().smp(),
                        () -> Duration.ofSeconds(shared.loaded().queue().get().smpIntervalSeconds()),
                        clock),
                clock);
        final PackStation packs = new PackStation(
                proxy,
                logger,
                shared.routing(),
                shared.seasonWatch(),
                shared.roster(),
                packMessages,
                config::get,
                book,
                shared.backendHealth());
        packs.registerChannel();
        return packs;
    }

    /** Creates the router and the sweep of the waiting room. */
    private PlayerRouter wireRouting(final Shared shared, final PackStation packs) {
        final RouteIntents intents = new RouteIntents(shared.roster(), shared.servers(), logger);
        proxy.getEventManager().register(this, intents);
        final PlayerRouter router = new PlayerRouter(
                scheduler,
                proxy,
                logger,
                shared.access(),
                shared.routing(),
                shared.seasonWatch(),
                shared.roster(),
                shared.fallback(),
                shared.gateMessages(),
                packs,
                intents,
                shared.backendHealth(),
                shared.parkedSeats(),
                shared.homecoming(),
                clock);
        shared.routerRef().set(router);
        packs.onRelease(router::releaseFromLimbo);
        proxy.getEventManager().register(this, router);
        proxy.getEventManager().register(this, packs);
        final Duration sweepInterval = Duration.ofSeconds(shared.loaded().gate().limboSweepIntervalSeconds());
        final var _ = scheduler.every(sweepInterval, sweepInterval, packs::sweep);
        // A waiting queue and an update's percentage change faster than the sweep runs, so they are swept every second.
        final var _ = scheduler.every(BUSY_TICK, BUSY_TICK, packs::sweepBusy);
        shared.seasonWatch().refresh();
        return router;
    }

    /** Creates the login gate and the expiry check. */
    private ExpiryWatch wireLoginGate(final Shared shared) {
        final GateSpec gate = shared.loaded().gate();
        final LoginGate loginGate = new LoginGate(
                logger,
                proxy,
                shared.access(),
                shared.fallback(),
                shared.roster(),
                shared.gateMessages(),
                gate,
                shared.loaded().players().get(),
                clock);
        final ExpiryWatch expiryWatch = new ExpiryWatch(
                scheduler,
                proxy,
                logger,
                shared.access(),
                shared.fallback(),
                shared.gateMessages(),
                Duration.ofMinutes(gate.expiryWarningLeadMinutes()),
                clock);
        proxy.getEventManager().register(this, loginGate);
        proxy.getEventManager().register(this, shared.roster());
        proxy.getEventManager().register(this, expiryWatch);
        proxy.getEventManager()
                .register(
                        this,
                        new BackendKick(
                                proxy,
                                shared.servers(),
                                shared.backendHealth(),
                                shared.gateMessages(),
                                shared.roster(),
                                logger,
                                Waiting.on(clock)));
        return expiryWatch;
    }

    /** Creates the server list ping. */
    private SnapshotStore wireServerList(final Shared shared) {
        final SnapshotStore snapshots = SnapshotStore.using(shared.loaded().pool(), logger);
        snapshots.refresh();
        proxy.getEventManager()
                .register(
                        this,
                        new NetworkPing(
                                proxy,
                                shared.loaded().players().get(),
                                shared.seasonWatch(),
                                snapshots,
                                shared.renderer(),
                                languages.locales()[0],
                                clock,
                                ServerIcon.load(dataDirectory, logger)));
        return snapshots;
    }

    /** Creates the writer of the player counts. */
    private OnlineWriter wireOnlineCounts(final Shared shared) {
        final HikariDataSource pool = shared.loaded().pool();
        final OnlineWriter onlineWriter = new OnlineWriter(
                proxy,
                shared.servers(),
                OnlineDirectory.using(pool, clock),
                OnlineRoster.using(pool, clock),
                shared.role(),
                logger,
                clock);
        onlineWriter.write();
        final var _ = scheduler.every(OnlineWriter.TICK, OnlineWriter.TICK, onlineWriter::tick);
        return onlineWriter;
    }

    /** Creates the play time writer and its flush timer. */
    private void wirePlaytime(final Shared shared) {
        final PlaytimeWriter writer =
                new PlaytimeWriter(PlaytimeStore.using(shared.loaded().pool()), shared.roster(), logger, clock);
        this.playtime = writer;
        proxy.getEventManager().register(this, writer);
        final Duration flushInterval = Duration.ofSeconds(shared.loaded().gate().playtimeFlushIntervalSeconds());
        final var _ = scheduler.every(flushInterval, flushInterval, writer::flushAll);
    }

    /** Creates the restart countdown and the evacuation of players. */
    private Updates wireUpdates(final Shared shared, final PackStation packs, final OnlineWriter onlineWriter) {
        final HikariDataSource pool = shared.loaded().pool();
        final RestartWatch restartWatch = new RestartWatch(
                scheduler,
                proxy,
                logger,
                UpdateDirectory.using(pool),
                shared.roster(),
                shared.renderer(),
                shared.servers(),
                clock);
        final Evacuation evacuation = new Evacuation(
                proxy, logger, UpdateDirectory.using(pool), shared.servers(), shared.homecoming(), clock);
        packs.whenUpdating(evacuation::isMoving);
        packs.whenHeld(evacuation::isHeld);
        packs.whenProgressing(evacuation::progress);
        packs.whenSwitching(evacuation::holdsEveryone);
        evacuation.whenReleased(() -> scheduler.execute(packs::sweep));
        onlineWriter.whenHurrying(() -> restartWatch.isCountingDown() || evacuation.isAnyMoving());
        return new Updates(restartWatch, evacuation);
    }

    /** Creates the proxy swap. */
    private ProxySwap wireProxySwap(final Shared shared, final Updates updates) {
        final ProxySwap swap = new ProxySwap(
                proxy,
                logger,
                UpdateDirectory.using(shared.loaded().pool()),
                shared.swaps(),
                shared.role(),
                shared.standbyAddress(),
                clock);
        updates.restartWatch().whenZeroReached(() -> atZero(updates.evacuation(), swap));
        updates.restartWatch().standbyProxyAnswers(swap::canPark);
        proxy.getEventManager()
                .register(
                        this,
                        new RestartGate(
                                logger,
                                swap::isStopping,
                                shared.parkedSeats()::holds,
                                swap::park,
                                shared.gateMessages(),
                                shared.fallback()));
        final StandbyReturn standbyReturn = new StandbyReturn(
                scheduler,
                proxy,
                logger,
                shared.swaps(),
                shared.role(),
                shared.publicAddress(),
                SwapAddresses.publicAddress(shared.loaded().network().liveAddress())
                        .orElse(null),
                clock,
                shared.homecoming());
        final var _ = scheduler.every(StandbyReturn.INTERVAL, StandbyReturn.INTERVAL, standbyReturn::check);
        logSwapPosture(shared, swap);
        return swap;
    }

    /** Logs how the proxy swap will run. */
    private void logSwapPosture(final Shared shared, final ProxySwap swap) {
        final InetSocketAddress publicAddress = shared.publicAddress();
        final InetSocketAddress standbyAddress = shared.standbyAddress();
        if (shared.role().isStandby()) {
            logger.info(
                    "standby proxy enabled: standby_room={} public_address={} live_address={}",
                    shared.servers().limboStandby(),
                    publicAddress == null ? "none" : publicAddress.getHostString() + ":" + publicAddress.getPort(),
                    shared.loaded().network().liveAddress());
        } else if (swap.isArmed() && standbyAddress != null) {
            logger.info(
                    "proxy swap armed: standby_address={}:{}",
                    standbyAddress.getHostString(),
                    standbyAddress.getPort());
        } else {
            logger.warn("public address missing, proxy swap off");
        }
    }

    /** Registers the command gate, the commands of the players and the command tree. */
    private void wireCommands(final Shared shared) {
        final PlayersSpec players = shared.loaded().players().get();
        proxy.getEventManager()
                .register(
                        this,
                        new CommandGate(
                                shared.roster(),
                                NetworkSettings.allowlist(players),
                                shared.renderer(),
                                logger,
                                ProxySounds.defaults(logger::warn)));
        logger.info("command allowlist loaded: commands=\"{}\"", players.commandAllowlist());
        final PrivateMessages privateMessages = new PrivateMessages(proxy, shared.roster(), shared.renderer(), logger);
        proxy.getEventManager().register(this, privateMessages);
        final InfoTexts infoTexts =
                new InfoTexts(shared.renderer(), shared.loaded().gate().discordInviteUrl(), shared.roster());
        final CommandManager commands = proxy.getCommandManager();
        final List<BrigadierCommand> registered = new ArrayList<>(privateMessages.commands());
        registered.addAll(infoTexts.commands());
        registered.forEach(command ->
                commands.register(commands.metaBuilder(command).plugin(this).build(), command));
        if (!shared.role().isStandby()) {
            final CommandTrees trees = CommandTrees.of(
                    commands,
                    proxy.getConsoleCommandSource(),
                    new CommandTreeWriter(CommandTreeStore.using(shared.loaded().pool()), "proxy"),
                    scheduler,
                    logger);
            trees.start();
        }
    }

    /** Opens the signal hub and subscribes the refreshes. It does not start the hub. */
    private SignalHub wireSignals(
            final Shared shared, final PlayerRouter router, final Updates updates, final Reads reads) {
        final Loaded loaded = shared.loaded();
        final SignalHub hub = openHub(loaded, "proxy-signals");
        this.signals = hub;
        hub.on(Channel.SEASON, "the season state", shared.seasonWatch()::refresh);
        loaded.settings().listen(hub, () -> reloadNetwork(loaded));
        MessageOverrideStore.using(loaded.pool()).follow(loaded.messages(), hub);
        documents.follow(DocumentStore.using(loaded.pool()), hub, () -> {});
        hub.on(Channel.ADMIN, "the admin roster", () -> refreshAdmins(shared, router));
        hub.on(Channel.UPDATE, "the restart countdown", updates.restartWatch()::check);
        hub.on(Channel.UPDATE, "the update evacuation", updates.evacuation()::check);
        hub.on(Channel.UPDATE, "the proxy swap", reads.swap()::check);
        hub.on(Channel.SEASON, "the players' access", () -> scheduler.execute(reads.expiry()::check));
        return hub;
    }

    /** Opens a signal hub for the server list numbers. It does not start the hub. */
    private SignalHub wireNumbers(final Shared shared, final SnapshotStore snapshots) {
        final SignalHub hub = openHub(shared.loaded(), "proxy-numbers");
        this.numberSignals = hub;
        snapshots.follow(hub, scheduler);
        return hub;
    }

    private SignalHub openHub(final Loaded loaded, final String name) {
        final DatabaseSpec database = loaded.database();
        return SignalHub.open(
                database.jdbcUrl(),
                database.username(),
                database.password(),
                database.queryTimeoutSeconds(),
                name,
                logger);
    }

    /** Reads the admins again and re-routes the players when an admin flag changed. */
    private void refreshAdmins(final Shared shared, final PlayerRouter router) {
        final int changed = shared.roster().refreshAdmins(shared.access().admins());
        if (changed > 0) {
            logger.info("admin flags changed, players re-routed: count={}", changed);
            router.rerouteAll(shared.seasonWatch().state());
        }
    }

    /** Logs the configuration. */
    private void logStartup(final Shared shared) {
        final GateSpec gate = shared.loaded().gate();
        final PlayersSpec players = shared.loaded().players().get();
        final SeasonState season = shared.seasonWatch().state();
        logger.info(
                "login gate up: season=\"{}\" query_timeout_seconds={} fallback_window_minutes={}"
                        + " playtime_flush_seconds={} waiting_room={} sweep_seconds={}",
                season,
                shared.loaded().database().queryTimeoutSeconds(),
                gate.fallbackCacheWindowMinutes(),
                gate.playtimeFlushIntervalSeconds(),
                shared.servers().limbo(),
                gate.limboSweepIntervalSeconds());
        logger.info("player limit set: max_players={}", players.maxPlayers());
        if (season.opening(clock.instant()) != SeasonOpening.LAUNCHED) {
            logger.info(
                    "network not open, admins only: countdown=\"{}\"",
                    shared.renderer()
                            .plainLine(Locale.ENGLISH, LaunchCountdown.line(season.launch(), clock.instant())));
        }
    }

    /** Reads the live groups again: players, crests and the queue. An invalid change keeps the old values. */
    private void reloadNetwork(final Loaded loaded) {
        final Setting<PrestigeSpec> prestigeSettings = loaded.prestigeSetting();
        try {
            if (loaded.pack().reload()) {
                logPack(loaded.pack().get(), "resource pack changed");
            }
        } catch (final SettingsException refused) {
            logger.warn("resource pack setting refused, running pack kept: cause={}", refused.getMessage());
        }
        try {
            loaded.players().reload();
        } catch (final SettingsException refused) {
            logger.warn("network setting refused, running value kept: cause={}", refused.getMessage());
        }
        try {
            prestigeSettings.reload();
            prestige = NetworkSettings.prestige(prestigeSettings.get());
        } catch (final SettingsException refused) {
            logger.warn("prestige tiers refused, running tiers kept: cause={}", refused.getMessage());
        }
        try {
            loaded.queue().reload();
        } catch (final SettingsException refused) {
            logger.warn("queue interval refused, running value kept: cause={}", refused.getMessage());
        }
    }

    private void logPack(final PackSpec pack, final String event) {
        if (pack.enabled()) {
            logger.info("{}: url={} sha1={} force={}", event, pack.url(), pack.sha1(), pack.force());
        } else {
            logger.warn("resource pack disabled");
        }
    }

    /** Runs the evacuation first and the proxy swap second. */
    private void atZero(final Evacuation evacuation, final ProxySwap swap) {
        evacuation.check();
        swap.check();
    }

    /** Starts the {@link Readiness} heartbeat. {@link #failClosed} does not call it. */
    private void startHeartbeat() {
        final Readiness readiness = Readiness.onDefaultPath(clock, logger::warn);
        heartbeat = scheduler.every(Duration.ZERO, Readiness.BEAT, readiness::refresh);
    }

    /** Registers a login handler that refuses every login. */
    private void failClosed(final Exception failure) {
        logger.error("proxy not started, every login refused", failure);

        try {
            final Messages shown = messages != null
                    ? messages
                    : Messages.load(getClass().getClassLoader(), "messages/database", languages.locales());
            proxy.getEventManager()
                    .register(
                            this,
                            new MisconfiguredGate(
                                    logger,
                                    MessageRenderer.of(shown)
                                            .reading(new AppliedDocuments(
                                                    getClass().getClassLoader(), Set.of("proxy")))));
        } catch (final RuntimeException broken) {
            logger.error("packaged messages unreadable, proxy stopped", broken);
            closeResources();
            proxy.shutdown();
            return;
        }

        closeResources();
    }

    @Subscribe
    public void onProxyShutdown(final ProxyShutdownEvent event) {
        if (playtime != null) {
            logger.info("play time flushed on shutdown: count={}", playtime.flushAll());
        }
        closeResources();
    }

    private void closeResources() {
        if (heartbeat != null) {
            heartbeat.cancel();
            heartbeat = null;
        }
        if (signals != null) {
            signals.close();
            signals = null;
        }
        if (numberSignals != null) {
            numberSignals.close();
            numberSignals = null;
        }
        if (pool != null) {
            pool.close();
            pool = null;
        }
    }
}
