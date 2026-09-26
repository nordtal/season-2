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
import eu.nordtal.s2.commands.phase.PhaseCommands;
import eu.nordtal.s2.commands.phase.PhaseEffects;
import eu.nordtal.s2.commands.remote.CommandInbox;
import eu.nordtal.s2.common.command.CommandRequests;
import eu.nordtal.s2.common.phase.SeasonDates;
import eu.nordtal.s2.common.command.AllowlistDirectory;
import eu.nordtal.s2.common.command.CommandAllowlist;
import eu.nordtal.s2.proxy.command.CommandGate;
import eu.nordtal.s2.proxy.command.InfoTexts;
import eu.nordtal.s2.proxy.command.PrivateMessages;
import eu.nordtal.s2.proxy.command.ProxyNetworkEffects;
import eu.nordtal.s2.proxy.command.VelocityCommands;
import eu.nordtal.s2.proxy.phase.ProxyPhaseEffects;
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
 * Owns the season 2 phase state machine, the access login gate and the network-wide play-time
 * counter.
 *
 * What is wired up here: {@link LoginGate}, the phase-aware login decision, one database round
 * trip carrying both the access state and the {@link SeasonPhase}; {@link PhaseWatch} plus a
 * {@link NotificationListener}, the 30-second poll and a dedicated {@code LISTEN nordtal_phase}
 * connection outside the pool - the poll is the guarantee, the listener only makes a switch feel
 * instant; {@code PhaseCommand}, the emergency {@code /phase}, authorised by
 * {@code discord_user.admin} through {@link LoginRoster}; {@link PlaytimeWriter},
 * {@code player_playtime}, written on disconnect and periodically in between;
 * {@link MisconfiguredGate}, the fail-closed handler, below; {@code PlayerRouter}, the limbo-first
 * login route and the phase-change re-route; and {@link PackStation}, the forced resource-pack
 * offer, the {@code nordtal:limbo} channel and the release out of the waiting room.
 *
 * Configuration failure fails closed: a bad {@code database.yml} or {@code gate.yml} registers a
 * {@code LoginEvent} handler that refuses everybody, which is the per-plugin disable Velocity does
 * not have. Admins are not exempted and cannot be - the admin flag lives in the database a bad
 * {@code database.yml} cannot reach.
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

    /**
     * Assigned after the listener above is started, and read by it.
     *
     * Volatile because the listener's own thread calls its refreshes the moment it connects,
     * which is before this line is reached - the same window {@code commandInbox} has, answered the
     * same way. The five-second poll covers it.
     */
    private volatile RestartWatch restartWatch;
    private volatile Evacuation evacuation;

    /**
     * Commands another process asked this one to run.
     *
     * A field because the notification listener is built before it and refers to it: the listener
     * carries {@code nordtal_command} alongside the phase and admin channels, on one connection.
     */
    private volatile CommandInbox commandInbox;
    /** {@code :commands}' bundle as the inbox renders it - a second view of the same files. */
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

        // The five reply colours, read once, here, the same as network.yml and gate.yml - see ColoursSpec.
        final ToneColours colours = ToneColours.parse(Configs.declared(coloursConfig), logger::warn);

        final PhaseDirectory phases = PhaseDirectory.using(pool);
        final GateMessages gateMessages = new GateMessages(messages, gateConfig);
        final FallbackCache fallback = new FallbackCache(Duration.ofMinutes(gateConfig.fallbackCacheWindowMinutes()));
        final LoginRoster roster = new LoginRoster();

        // PlayerRouter is the phase-change listener; ONE PhaseServers for the whole plugin, not one per caller.
        final PhaseServers phaseServers = PhaseServers.from(gateConfig);

        // Which of the two proxies this is: both containers are the same image; nothing can be worked out by looking.
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
            // Not fatal: a seat only changes where an admin lands, and refusing to start over that trades the network.
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

        // Every destination this plugin chooses is recorded, every other refused - Velocity's /server was open to all.
        final RouteIntents intents =
                new RouteIntents(roster, phaseServers, logger);
        proxy.getEventManager().register(this, intents);

        // The way back has a voice too: one object for both returns, one sentence said twice on the same register.
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

        // The admin roster rides the phase's two signals; the whole set is re-derived, rerouteAll only on change.
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
            // One connection, every refresh on every signal - see eu.nordtal.s2.common.notify.
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
                            // One connection, three channels: every refresh runs on every signal regardless.
                            new NotificationListener.Refresh("the command inbox", () -> {
                                // Null until the command layer is built further down; the poll covers that window.
                                final CommandInbox inbox = commandInbox;
                                if (inbox != null) {
                                    inbox.drain();
                                }
                            }),
                            // The countdown, same reason and null guard: latency here would drop the "30s" beat.
                            new NotificationListener.Refresh("the restart countdown", () -> {
                                final RestartWatch watch = restartWatch;
                                if (watch != null) {
                                    watch.check();
                                }
                            }),
                            // And the evacuation on the same signal: an eight-second window makes it matter.
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

        // This proxy already knows every connection; it writes counts and who is connected in one pass per tick.
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

        // The proxy is the only process that sees every player, so it warns them, towards the row's own instant.
        this.restartWatch = new RestartWatch(this, proxy, logger,
                UpdateDirectory.using(pool), roster, messages, phaseServers, Clock.systemUTC());
        proxy.getScheduler().buildTask(this, this.restartWatch::check)
                .delay(RestartWatch.INTERVAL)
                .repeat(RestartWatch.INTERVAL)
                .schedule();

        // Warning is half of it; the other half moves players into the waiting room, its own task off the countdown.
        this.evacuation = new Evacuation(proxy, logger,
                UpdateDirectory.using(pool), phaseServers, homecoming);
        packs.whenUpdating(this.evacuation::isMoving);
        packs.whenHeld(this.evacuation::isHeld);
        // The counts get their fast cadence from the countdown, not the move - see OnlineWriter#tick.
        onlineWriter.whenHurrying(() ->
                this.restartWatch.isCountingDown() || this.evacuation.isAnyMoving());
        // The moment, not the window: the countdown already schedules a task on the instant, handed on here.
        proxy.getScheduler().buildTask(this, this.evacuation::check)
                .delay(RestartWatch.INTERVAL)
                .repeat(RestartWatch.INTERVAL)
                .schedule();

        // What Evacuation cannot do, since its own process is being stopped; `role` decides which of the two acts.
        final ProxySwap swap = new ProxySwap(proxy, logger, UpdateDirectory.using(pool), swaps,
                role, standbyAddress, Clock.systemUTC());
        // On the same moment, and second: the order is the order a player travels, backends first then the network.
        this.restartWatch.whenZeroReached(() -> {
            this.evacuation.check();
            swap.check();
        });
        // And the announcement learns whether there is a standby: loading screen or thrown out, once per countdown.
        this.restartWatch.standbyProxyAnswers(swap::canPark);
        proxy.getScheduler().buildTask(this, swap::check)
                .delay(RestartWatch.INTERVAL)
                .repeat(RestartWatch.INTERVAL)
                .schedule();

        // The park is a moment, the door a state: an arrival in the gap gets a sentence and goes to the standby too.
        proxy.getEventManager().register(this,
                new RestartGate(logger, swap::isStopping, parkedSeats::holds, swap::park,
                        gateMessages, fallback));

        final StandbyReturn standbyReturn = new StandbyReturn(this, proxy, logger, swaps, role,
                publicAddress, Clock.systemUTC(), homecoming);
        proxy.getScheduler().buildTask(this, standbyReturn::check)
                .delay(StandbyReturn.INTERVAL)
                .repeat(StandbyReturn.INTERVAL)
                .schedule();

        // Said at start, not on the day it matters: a silent swap failure looks like a network that just went down.
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

        // Every decision lives in :commands, shared with the bot; the proxy registers only its own commands here.
        final PhaseEffects phaseEffects =
                new ProxyPhaseEffects(this, proxy, logger, phases, phaseWatch);
        final NetworkEffects networkEffects = new ProxyNetworkEffects(
                ProxyNetworkEffects.async(this, proxy), messages, sharedMessages, logger);

        final VelocityCommands tree = new VelocityCommands(proxy, roster, messages, () -> colours);
        PhaseCommands.all().forEach(command -> tree.local(command, phaseEffects));
        NetworkCommands.all().forEach(command -> tree.local(command, networkEffects));

        // /update is Target.LOCAL: the proxy writes the row itself, and the watcher is not optional for the answer.
        final eu.nordtal.s2.proxy.update.UpdateWatch updateWatch =
                new eu.nordtal.s2.proxy.update.UpdateWatch(this, proxy, logger,
                        UpdateDirectory.using(pool), Clock.systemUTC());
        final eu.nordtal.s2.commands.update.UpdateEffects updateEffects =
                new eu.nordtal.s2.commands.update.DirectoryUpdateEffects(
                        UpdateDirectory.using(pool),
                        ProxyNetworkEffects.async(this, proxy)::execute,
                        (what, failure) -> logger.warn("An update command failed while "
                                + what, failure),
                        updateWatch::watch);
        eu.nordtal.s2.commands.update.UpdateCommands.all()
                .forEach(command -> tree.local(command, updateEffects));
        // The five a player types, natively: plain Velocity Brigadier, not built through `tree`, and not admin-only.
        final PrivateMessages privateMessages =
                new PrivateMessages(proxy, roster, messages, () -> colours, logger);
        // A listener as well as a command: it holds who last spoke to whom, for /r, dropped when somebody leaves.
        proxy.getEventManager().register(this, privateMessages);

        // The invite is gate.yml's, the same string every login screen already uses.
        final InfoTexts infoTexts =
                new InfoTexts(messages, gateConfig.discordInviteUrl(), roster);

        // "clear" is not guessable and is the only value of this argument that is not a date.
        tree.suggest(PhaseCommands.LAUNCH, "when", () -> List.of(SeasonDates.CLEAR));
        tree.suggest(PhaseCommands.SMP_START, "when", () -> List.of(SeasonDates.CLEAR));

        final CommandManager commands = proxy.getCommandManager();
        final List<com.velocitypowered.api.command.BrigadierCommand> registered =
                new java.util.ArrayList<>(tree.build());
        registered.addAll(privateMessages.commands());
        registered.addAll(infoTexts.commands());
        registered.forEach(command -> commands.register(
                commands.metaBuilder(command).plugin(this).build(), command));

        // The proxy's own inbox: /network reload is a request row; /phase does not travel, the row is the state.
        commandInbox = new CommandInbox(Target.PROXY,
                CommandRequests.borrowing(pool),
                // :commands' bundle alone - the layered `messages` allows MiniMessage an admin would read literally.
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
     * The container readiness marker - see {@link Readiness}, and note where this call sits.
     *
     * It is the last thing {@link #start} does, and {@link #failClosed} does not call it at all.
     * That is the whole point on this service: a proxy whose configuration is broken is up,
     * bound to 25565 and answering pings, while refusing every login there is. "The proxy is up and
     * the gate is off" announced itself nowhere until this marker existed - a port check cannot see
     * it, because the port is exactly what still works.
     */
    private void startHeartbeat() {
        final Readiness readiness = Readiness.onDefaultPath(logger::warn);
        heartbeat = proxy.getScheduler().buildTask(this, readiness::refresh)
                .delay(Duration.ZERO)
                .repeat(Readiness.BEAT)
                .schedule();
    }

    /**
     * The fail-closed rule: nothing else has been registered by the time this runs, so this handler
     * is the only thing that sees a login, and it refuses every one of them.
     */
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
            // The last slice of every connected session, so a planned restart costs nobody time since their last flush.
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
        // access.close() is a no-op (it never owns the pool); this proxy built the pool with AccessPool and closes it.
        if (pool != null) {
            pool.close();
            pool = null;
        }
        access = null;
    }
}
