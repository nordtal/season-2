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
 * Wires the season 2 proxy: the login gate, phase routing, play time, the pack station and update handling.
 *
 * A bad {@code database} group or the {@code gate} group fails closed: every login is refused, admins included.
 */
@Plugin(
        id = "proxy",
        name = "proxy",
        version = ProxyVersion.VALUE,
        description = "Season 2 phase control and backend routing.",
        url = "https://nordtal.eu",
        authors = {"nordtal"})
public final class ProxyPlugin {

    /** The one clock of this process. */
    private final Clock clock = NetworkTime.clock();

    /** The languages of the network's settings, or their defaults while those cannot be read. */
    private Languages languages = NetworkSettings.defaultLanguages();

    /** The proxy's one bundle, once the settings named its languages; the refusal screen reuses it. */
    private @Nullable Messages messages;

    /** The crest table every card is drawn by, as the network's prestige group last read; the hub's thread sets it. */
    private volatile Prestige prestige = Prestige.defaults();

    private final ProxyServer proxy;
    private final Logger logger;

    /** The one way this proxy runs work later or again; nothing else here touches Velocity's scheduler. */
    private final VelocityScheduler scheduler;

    private final Path dataDirectory;

    private @Nullable HikariDataSource pool;
    private @Nullable SignalHub signals;
    private @Nullable SignalHub numberSignals;
    private @Nullable PlaytimeWriter playtime;
    private Scheduler.@Nullable Task heartbeat;

    /** The settings groups once read, with the pool they were read through. */
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

    /** What the features of the proxy are wired from. */
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

    /** The two update duties the signal hub wakes. */
    private record Updates(RestartWatch restartWatch, Evacuation evacuation) {}

    /** The other reads of database state, which the signal hub wakes too and no timer repeats. */
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

    /** Reads every settings group this proxy runs on, opening the pool first. */
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
        // The reply and tone colours, read once here; see Colours.GROUP.
        final ToneColours colours =
                ToneColours.parse(Colours.declared(settings.load(Colours.GROUP).get()), logger::warn);
        final Setting<PlayersSpec> players = settings.load(NetworkSettings.PLAYERS);
        final Setting<PrestigeSpec> prestigeSetting = settings.load(NetworkSettings.PRESTIGE);
        prestige = NetworkSettings.prestige(prestigeSetting.get());
        final SeasonSpec season = settings.load(NetworkSettings.SEASON).get();
        final LanguageAndTimeSpec languageAndTime =
                settings.load(NetworkSettings.LANGUAGE_AND_TIME).get();
        languages = NetworkSettings.languages(languageAndTime);
        // After the settings, which name the languages and the season; the database bundle holds the hover card.
        final Messages loadedMessages = Messages.load(
                        getClass().getClassLoader(),
                        List.of("messages/database", "messages/proxy"),
                        languages.locales())
                .within(NetworkSettings.environment("proxy", season, languageAndTime, colours));
        this.messages = loadedMessages;
        return new Loaded(
                opened, database, gate, pack, network, colours, loadedMessages, settings, players, prestigeSetting);
    }

    /** Wires every feature in the order their event handlers must register, then starts the signal hub and the beat. */
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

    /** Builds what several features share. */
    private Shared shared(final Loaded loaded) {
        final HikariDataSource pool = loaded.pool();
        final AccessDirectory access = AccessDirectory.using(pool, clock);
        final LoginRoster roster = new LoginRoster();
        // The proxy's one renderer: a name is drawn bare, with the card of the player's login here.
        final MessageRenderer renderer =
                MessageRenderer.of(loaded.messages(), Names.BARE, roster.cards(() -> prestige));
        // One PhaseServers for the whole plugin, not one per caller.
        final PhaseServers phaseServers = PhaseServers.from(loaded.gate());
        // Both proxies are the same image, so the role comes from configuration.
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
                new Homecoming(logger, renderer, roster, phaseServers),
                routerRef);
    }

    /** Reads where players stood before the last proxy swap, once, before Velocity binds its listener. */
    private ParkedSeats takeParkedSeats(final SwapStore swaps) {
        final ParkedSeats parked;
        try {
            // The statement empties the table, so a second read would find nothing.
            parked = new ParkedSeats(swaps.takeAllSeats(), clock.instant());
        } catch (final RuntimeException failure) {
            // Not fatal: a seat only changes where an admin lands.
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

    /** Offers the resource pack and holds players in the waiting room until they have applied it. */
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

    /** Routes players by the season phase and sweeps the waiting room. */
    private PlayerRouter wireRouting(final Shared shared, final PackStation packs) {
        // Every destination this plugin chooses is recorded, and every other refused.
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
        // Read once, before the first player arrives, so the MAINTENANCE fallback runs as briefly as possible.
        shared.phaseWatch().refresh();
        return router;
    }

    /** Refuses or admits each login and warns players whose access is about to end. */
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
        // A kick with a reason keeps the backend's screen; one with none goes to the waiting room. See BackendKick.
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

    /** Answers the server list from numbers the signal hub refreshes, never on the unauthenticated ping itself. */
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

    /** Writes the counts and who is connected, since this proxy knows every connection. */
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

    /** Counts each session's play time and flushes it on a timer. */
    private void wirePlaytime(final Shared shared) {
        final PlaytimeWriter writer =
                new PlaytimeWriter(PlaytimeStore.using(shared.loaded().pool()), shared.roster(), logger, clock);
        this.playtime = writer;
        proxy.getEventManager().register(this, writer);
        final Duration flushInterval = Duration.ofSeconds(shared.loaded().gate().playtimeFlushIntervalSeconds());
        final var _ = scheduler.every(flushInterval, flushInterval, writer::flushAll);
    }

    /** Warns of a restart, then moves players into the waiting room on a task of its own beside the countdown. */
    private Updates wireUpdates(final Shared shared, final PackStation packs, final OnlineWriter onlineWriter) {
        final HikariDataSource pool = shared.loaded().pool();
        // Only the proxy sees every player, so it gives the warning, counting towards the row's instant.
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
        // The counts hurry from the countdown on, not from the move; see OnlineWriter#tick.
        onlineWriter.whenHurrying(() -> restartWatch.isCountingDown() || evacuation.isAnyMoving());
        // The countdown schedules a task on the zero instant and the commit's notification follows; both wake it.
        return new Updates(restartWatch, evacuation);
    }

    /** Parks the network on the standby proxy when this one stops, and takes it back when this one is the standby. */
    private ProxySwap wireProxySwap(final Shared shared, final Updates updates) {
        final ProxySwap swap = new ProxySwap(
                proxy,
                logger,
                UpdateDirectory.using(shared.loaded().pool()),
                shared.swaps(),
                shared.role(),
                shared.standbyAddress(),
                clock);
        // Second at zero, beside the announcement.
        updates.restartWatch().whenZeroReached(() -> atZero(updates.evacuation(), swap));
        // Lets the announcement say whether a standby catches players, once per countdown.
        updates.restartWatch().standbyProxyAnswers(swap::canPark);
        // An arrival between zero and the stop gets a sentence and goes to the standby too.
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
                clock,
                shared.homecoming());
        final var _ = scheduler.every(StandbyReturn.INTERVAL, StandbyReturn.INTERVAL, standbyReturn::check);
        logSwapPosture(shared, swap);
        return swap;
    }

    /** Says at start how a swap will go, since a silent swap failure would look like a network that went down. */
    private void logSwapPosture(final Shared shared, final ProxySwap swap) {
        final InetSocketAddress publicAddress = shared.publicAddress();
        final InetSocketAddress standbyAddress = shared.standbyAddress();
        if (shared.role().isStandby()) {
            logger.info(
                    "THIS IS THE STANDBY PROXY. Arrivals are held in '{}' and transferred back "
                            + "to {} as soon as it answers again; no player counts are written "
                            + "from here.",
                    shared.phaseServers().limboStandby(),
                    publicAddress == null
                            ? "nowhere - network#public-address is empty"
                            : publicAddress.getHostString() + ":" + publicAddress.getPort());
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

    /** Registers the allowlist gate, the commands a player types and the console's command suggestions. */
    private void wireCommands(final Shared shared) {
        final PlayersSpec players = shared.loaded().players().get();
        final ToneColours colours = shared.loaded().colours();
        // One list, the network's, enforced here and read by every Paper server too. See CommandGate/CommandFilter.
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
        // The five a player types, as plain Velocity Brigadier, not admin-only.
        final PrivateMessages privateMessages =
                new PrivateMessages(proxy, shared.roster(), shared.renderer(), () -> colours, logger);
        // Also a listener: it tracks who last spoke to whom for /r, dropped when somebody leaves.
        proxy.getEventManager().register(this, privateMessages);
        // The invite is the gate group's, the same string every login screen already uses.
        final InfoTexts infoTexts =
                new InfoTexts(shared.renderer(), shared.loaded().gate().discordInviteUrl(), shared.roster());
        final CommandManager commands = proxy.getCommandManager();
        final List<BrigadierCommand> registered = new ArrayList<>(privateMessages.commands());
        registered.addAll(infoTexts.commands());
        registered.forEach(command ->
                commands.register(commands.metaBuilder(command).plugin(this).build(), command));
        // The standby runs the same commands, so the console's suggestions come from the proxy players type in.
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

    /** Opens the proxy's one LISTEN connection and subscribes every refresh to its channel, without starting it. */
    private SignalHub wireSignals(
            final Shared shared, final PlayerRouter router, final Updates updates, final Reads reads) {
        final Loaded loaded = shared.loaded();
        final SignalHub hub = openHub(loaded, "proxy-signals");
        this.signals = hub;
        hub.on(Channel.PHASE, "the season phase", shared.phaseWatch()::refresh);
        // The network's limit and allowlist, changed in Steward, arrive here without a restart.
        loaded.settings().listen(hub, () -> reloadNetwork(loaded.players(), loaded.prestigeSetting()));
        MessageOverrideStore.using(loaded.pool()).follow(loaded.messages(), hub);
        hub.on(Channel.ADMIN, "the admin roster", () -> refreshAdmins(shared, router));
        // Latency here would drop the 30 second beat.
        hub.on(Channel.UPDATE, "the restart countdown", updates.restartWatch()::check);
        hub.on(Channel.UPDATE, "the update evacuation", updates.evacuation()::check);
        hub.on(Channel.UPDATE, "the proxy swap", reads.swap()::check);
        // Every wake-up also runs this, so a change no signal announced waits one quiet minute at most.
        hub.on(Channel.PHASE, "the players' access", () -> scheduler.execute(reads.expiry()::check));
        return hub;
    }

    /**
     * Opens the server list numbers' own LISTEN connection, without starting it.
     *
     * Apart because the SMP channel signals on every flush, and a wake-up runs every refresh of its hub.
     */
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

    /** Re-reads the admin roster, which rides the phase's signals; re-routes only when somebody's flag changed. */
    private void refreshAdmins(final Shared shared, final PlayerRouter router) {
        final int changed = shared.roster().refreshAdmins(shared.access().admins());
        if (changed > 0) {
            logger.info("The admin flag changed for {} connected player(s); re-routing", changed);
            router.rerouteAll(shared.phaseWatch().lastKnown());
        }
    }

    /** Says at start what the proxy runs with. */
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

    /** Takes the network's limit, allowlist and crest table again; a refused change keeps what runs. */
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

    /** What the zero beat moves: the backends first, then the network, the order a player travels. */
    private void atZero(final Evacuation evacuation, final ProxySwap swap) {
        evacuation.check();
        swap.check();
    }

    /**
     * Starts the container readiness marker (see {@link Readiness}) as the last step of {@link #start}.
     *
     * {@link #failClosed} never calls it, so a proxy refusing every login never reports ready.
     */
    private void startHeartbeat() {
        final Readiness readiness = Readiness.onDefaultPath(clock, logger::warn);
        heartbeat = scheduler.every(Duration.ZERO, Readiness.BEAT, readiness::refresh);
    }

    /** Registers the only login handler, which refuses everybody. */
    private void failClosed(final Exception failure) {
        logger.error("proxy could not start, so NOBODY will be let onto this network. "
                + "Fix the configuration and restart the proxy.");
        logger.error("{}", failure.getMessage(), failure);

        // The bundle when the failure came after it, else the packaged one: the screen needs no database.
        try {
            final Messages shown = messages != null
                    ? messages
                    : Messages.load(getClass().getClassLoader(), "messages/proxy", languages.locales());
            proxy.getEventManager().register(this, new MisconfiguredGate(logger, MessageRenderer.of(shown)));
        } catch (final RuntimeException broken) {
            // The packaged bundle is inside the jar; reaching here means it is damaged, so the proxy shuts down.
            logger.error(
                    "proxy cannot even load its own packaged messages, so it cannot "
                            + "put up a refusal screen. Stopping the proxy - that is the only way left to "
                            + "refuse everybody.",
                    broken);
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
