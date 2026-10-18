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
import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.common.health.Readiness;
import eu.nordtal.season.common.language.Languages;
import eu.nordtal.season.common.time.NetworkTime;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.access.AccessDirectory;
import eu.nordtal.season.database.access.Prestige;
import eu.nordtal.season.database.command.CommandTreeStore;
import eu.nordtal.season.database.command.CommandTreeWriter;
import eu.nordtal.season.database.message.MessageOverrideStore;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.database.online.OnlineDirectory;
import eu.nordtal.season.database.online.OnlineRoster;
import eu.nordtal.season.database.phase.PhaseDirectory;
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
import eu.nordtal.season.proxy.launch.LaunchCountdown;
import eu.nordtal.season.proxy.online.OnlineWriter;
import eu.nordtal.season.proxy.pack.PackMessages;
import eu.nordtal.season.proxy.pack.PackOffer;
import eu.nordtal.season.proxy.pack.PackStation;
import eu.nordtal.season.proxy.pack.WaitingBook;
import eu.nordtal.season.proxy.phase.PhaseWatch;
import eu.nordtal.season.proxy.ping.NetworkPing;
import eu.nordtal.season.proxy.ping.ServerIcon;
import eu.nordtal.season.proxy.ping.SnapshotStore;
import eu.nordtal.season.proxy.playtime.PlaytimeStore;
import eu.nordtal.season.proxy.playtime.PlaytimeWriter;
import eu.nordtal.season.proxy.routing.PhaseRouting;
import eu.nordtal.season.proxy.routing.RouteIntents;
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
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Creates the features of the proxy. An invalid {@code database} or {@code gate} group makes the proxy refuse every
 * login, admins included.
 */
@Plugin(
        id = "proxy",
        name = "proxy",
        version = ProxyVersion.VALUE,
        description = "Season 2 phase control and backend routing.",
        url = "https://nordtal.eu",
        authors = {"nordtal"})
public final class ProxyPlugin {

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
    private @Nullable SignalHub signals;
    private @Nullable SignalHub numberSignals;
    private @Nullable PlaytimeWriter playtime;
    private Scheduler.@Nullable Task heartbeat;

    /** The settings groups and the pool. */
    private record Loaded(
            HikariDataSource pool,
            DatabaseSpec database,
            GateSpec gate,
            PackSpec pack,
            NetworkSpec network,
            ToneColours colours,
            Messages messages,
            DatabaseSettings settings,
            Setting<PlayersSpec> players,
            Setting<PrestigeSpec> prestigeSetting) {}

    /** The objects that several features use. */
    private record Shared(
            Loaded loaded,
            AccessDirectory access,
            LoginRoster roster,
            MessageRenderer renderer,
            GateMessages gateMessages,
            FallbackCache fallback,
            PhaseServers phaseServers,
            ProxyRole role,
            PhaseRouting routing,
            PhaseWatch phaseWatch,
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
                "proxy enabled, {} backends registered", proxy.getAllServers().size());
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
        final GateSpec gate = settings.load(Group.of("gate", GateSpec.class).checkedBy(ProxySettings::checkGate))
                .get();
        final PackSpec pack = settings.load(Group.of("pack", PackSpec.class).checkedBy(ProxySettings::checkPack))
                .get();
        final NetworkSpec network = settings.load(
                        Group.of("network", NetworkSpec.class).whileRunning())
                .get();
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
                        getClass().getClassLoader(),
                        List.of("messages/database", "messages/proxy"),
                        languages.locales())
                .within(NetworkSettings.environment("proxy", season, languageAndTime, colours));
        this.messages = loadedMessages;
        return new Loaded(
                opened, database, gate, pack, network, colours, loadedMessages, settings, players, prestigeSetting);
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
    }

    /** Creates the objects that several features use. */
    private Shared shared(final Loaded loaded) {
        final HikariDataSource pool = loaded.pool();
        final AccessDirectory access = AccessDirectory.using(pool, clock);
        final LoginRoster roster = new LoginRoster();
        final MessageRenderer renderer =
                MessageRenderer.of(loaded.messages(), Names.BARE, roster.cards(() -> prestige));
        final PhaseServers phaseServers = PhaseServers.from(loaded.gate());
        final ProxyRole role = ProxyRole.of(loaded.network().standby());
        final InetSocketAddress publicAddress =
                SwapAddresses.publicAddress(loaded.network().publicAddress()).orElse(null);
        final InetSocketAddress standbyAddress = publicAddress == null
                ? null
                : SwapAddresses.standbyAddress(publicAddress, loaded.network().standbyPort())
                        .orElse(null);
        final SwapStore swaps = SwapStore.using(pool);
        final AtomicReference<PlayerRouter> routerRef = new AtomicReference<>();
        final PhaseWatch phaseWatch = new PhaseWatch(PhaseDirectory.using(pool, clock), logger, (previous, current) -> {
            final PlayerRouter router = routerRef.get();
            if (router != null) {
                router.onPhaseChanged(previous, current);
            }
        });
        return new Shared(
                loaded,
                access,
                roster,
                renderer,
                new GateMessages(renderer, loaded.gate()),
                new FallbackCache(Duration.ofMinutes(loaded.gate().fallbackCacheWindowMinutes()), clock),
                phaseServers,
                role,
                new PhaseRouting(phaseServers, role),
                phaseWatch,
                takeParkedSeats(swaps),
                swaps,
                publicAddress,
                standbyAddress,
                new BackendHealth(clock),
                new Homecoming(
                        logger,
                        renderer,
                        roster,
                        phaseServers,
                        UpdateDirectory.using(pool).recorder()),
                routerRef);
    }

    /** Reads the servers of the players before the last proxy swap. */
    private ParkedSeats takeParkedSeats(final SwapStore swaps) {
        final ParkedSeats parked;
        try {
            parked = new ParkedSeats(swaps.takeAllSeats(), clock.instant());
        } catch (final RuntimeException failure) {
            logger.warn(
                    "Could not read where players were standing before the last proxy swap; "
                            + "everybody will be routed by the season phase",
                    failure);
            return new ParkedSeats(List.of(), clock.instant());
        }
        if (parked.size() > 0) {
            logger.info("Came back from a proxy swap: {} player(s) have a seat waiting", parked.size());
        }
        return parked;
    }

    /** Creates the pack station. */
    private PackStation wirePackStation(final Shared shared) {
        final PackSpec config = shared.loaded().pack();
        final PackMessages packMessages = new PackMessages(shared.renderer());
        final PackOffer offer = config.enabled() ? new PackOffer(proxy, config, packMessages) : null;
        if (offer == null) {
            logger.warn(
                    "pack#enabled is false: NO RESOURCE PACK IS OFFERED. Players still pass "
                            + "through '{}', but every glyph in the tab list, the nametags, the boards and "
                            + "the HUD will render as a missing-glyph box.",
                    shared.phaseServers().limbo());
        } else {
            logger.info(
                    "Offering the resource pack from {} (sha1 {}, forced: {})",
                    config.url(),
                    config.sha1(),
                    config.force());
        }
        final WaitingBook book = new WaitingBook(
                offer != null,
                Duration.ofSeconds(config.applyTimeoutSeconds()),
                Duration.ofSeconds(shared.loaded().gate().limboReadyGraceSeconds()),
                shared.role(),
                clock);
        final PackStation packs = new PackStation(
                proxy,
                logger,
                shared.routing(),
                shared.phaseWatch(),
                shared.roster(),
                packMessages,
                config,
                offer,
                book,
                shared.backendHealth());
        packs.registerChannel();
        return packs;
    }

    /** Creates the router and the sweep of the waiting room. */
    private PlayerRouter wireRouting(final Shared shared, final PackStation packs) {
        final RouteIntents intents = new RouteIntents(shared.roster(), shared.phaseServers(), logger);
        proxy.getEventManager().register(this, intents);
        final PlayerRouter router = new PlayerRouter(
                scheduler,
                proxy,
                logger,
                shared.access(),
                shared.routing(),
                shared.phaseWatch(),
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
        shared.phaseWatch().refresh();
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
                                shared.phaseServers(),
                                shared.backendHealth(),
                                shared.gateMessages(),
                                shared.roster(),
                                logger));
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
                                shared.phaseWatch(),
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
                shared.phaseServers(),
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
                shared.phaseServers(),
                clock);
        final Evacuation evacuation =
                new Evacuation(proxy, logger, UpdateDirectory.using(pool), shared.phaseServers(), shared.homecoming());
        packs.whenUpdating(evacuation::isMoving);
        packs.whenHeld(evacuation::isHeld);
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
                    "THIS IS THE STANDBY PROXY. Arrivals are held in '{}' and transferred back "
                            + "to {} as soon as {} answers again; no player counts are written "
                            + "from here.",
                    shared.phaseServers().limboStandby(),
                    publicAddress == null
                            ? "nowhere - network#public-address is empty"
                            : publicAddress.getHostString() + ":" + publicAddress.getPort(),
                    shared.loaded().network().liveAddress());
        } else if (swap.isArmed() && standbyAddress != null) {
            logger.info(
                    "An update that moves this proxy will park everybody on {}:{} instead of " + "disconnecting them",
                    standbyAddress.getHostString(),
                    standbyAddress.getPort());
        } else {
            logger.warn("network#public-address is empty or carries no port, so an update that "
                    + "moves this proxy will DISCONNECT every connected player. That is the old "
                    + "behaviour and a valid choice; set it to the host:port players type to swap "
                    + "proxies instead.");
        }
    }

    /** Registers the command gate, the commands of the players and the command tree. */
    private void wireCommands(final Shared shared) {
        final PlayersSpec players = shared.loaded().players().get();
        final ToneColours colours = shared.loaded().colours();
        proxy.getEventManager()
                .register(
                        this,
                        new CommandGate(
                                shared.roster(),
                                NetworkSettings.allowlist(players),
                                shared.renderer(),
                                logger,
                                () -> colours,
                                ProxySounds.defaults(logger::warn)));
        logger.info("Players who are not admins may use: {}", players.commandAllowlist());
        final PrivateMessages privateMessages =
                new PrivateMessages(proxy, shared.roster(), shared.renderer(), () -> colours, logger);
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
        hub.on(Channel.PHASE, "the season phase", shared.phaseWatch()::refresh);
        loaded.settings().listen(hub, () -> reloadNetwork(loaded.players(), loaded.prestigeSetting()));
        MessageOverrideStore.using(loaded.pool()).follow(loaded.messages(), hub);
        hub.on(Channel.ADMIN, "the admin roster", () -> refreshAdmins(shared, router));
        hub.on(Channel.UPDATE, "the restart countdown", updates.restartWatch()::check);
        hub.on(Channel.UPDATE, "the update evacuation", updates.evacuation()::check);
        hub.on(Channel.UPDATE, "the proxy swap", reads.swap()::check);
        hub.on(Channel.PHASE, "the players' access", () -> scheduler.execute(reads.expiry()::check));
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
            logger.info("The admin flag changed for {} connected player(s); re-routing", changed);
            router.rerouteAll(shared.phaseWatch().lastKnown());
        }
    }

    /** Logs the configuration. */
    private void logStartup(final Shared shared) {
        final GateSpec gate = shared.loaded().gate();
        final PlayersSpec players = shared.loaded().players().get();
        final SeasonPhase phase = shared.phaseWatch().lastKnown();
        logger.info(
                "Access login gate is up in phase {} (query timeout {}s, fallback cache window "
                        + "{}m, play time flushed every "
                        + "{}s, waiting room '{}' swept every {}s)",
                phase,
                shared.loaded().database().queryTimeoutSeconds(),
                gate.fallbackCacheWindowMinutes(),
                gate.playtimeFlushIntervalSeconds(),
                shared.phaseServers().limbo(),
                gate.limboSweepIntervalSeconds());
        logger.info("The network takes {} players, enforced here alone.", players.maxPlayers());
        if (phase == SeasonPhase.PRE_LAUNCH) {
            logger.info(
                    "The network has not opened yet: only admins get in, everybody else is shown "
                            + "the countdown ({}).",
                    shared.loaded()
                            .messages()
                            .format(
                                    Locale.ENGLISH,
                                    LaunchCountdown.left(
                                            shared.phaseWatch().launch().orElse(null), clock.instant())));
        }
    }

    /** Reads the player limit, the allowlist and the crests again. An invalid change keeps the old values. */
    private void reloadNetwork(final Setting<PlayersSpec> players, final Setting<PrestigeSpec> prestigeSettings) {
        try {
            players.reload();
        } catch (final SettingsException refused) {
            logger.warn("A network setting was not taken, the running one stays: {}", refused.getMessage());
        }
        try {
            prestigeSettings.reload();
            prestige = NetworkSettings.prestige(prestigeSettings.get());
        } catch (final SettingsException refused) {
            logger.warn("The prestige tiers were not taken, the running ones stay: {}", refused.getMessage());
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
        logger.error("proxy could not start, so NOBODY will be let onto this network. "
                + "Fix the configuration and restart the proxy.");
        logger.error("{}", failure.getMessage(), failure);

        try {
            final Messages shown = messages != null
                    ? messages
                    : Messages.load(getClass().getClassLoader(), "messages/proxy", languages.locales());
            proxy.getEventManager().register(this, new MisconfiguredGate(logger, MessageRenderer.of(shown)));
        } catch (final RuntimeException broken) {
            logger.error(
                    "proxy cannot even load its own packaged messages, so it cannot "
                            + "put up a refusal screen. Stopping the proxy - that is the only way left to "
                            + "refuse everybody.",
                    broken);
            closeResources();
            proxy.shutdown();
            return;
        }

        closeResources();
    }

    @Subscribe
    public void onProxyShutdown(final ProxyShutdownEvent event) {
        if (playtime != null) {
            logger.info("Flushed play time for {} players on shutdown", playtime.flushAll());
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
