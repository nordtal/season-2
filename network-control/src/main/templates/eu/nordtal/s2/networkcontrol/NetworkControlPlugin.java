package eu.nordtal.s2.networkcontrol;

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
import eu.nordtal.s2.common.phase.PhaseDirectory;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.networkcontrol.config.Configs;
import eu.nordtal.s2.networkcontrol.config.DatabaseSpec;
import eu.nordtal.s2.networkcontrol.config.GateSpec;
import eu.nordtal.s2.networkcontrol.config.NetworkSpec;
import eu.nordtal.s2.networkcontrol.config.PackSpec;
import eu.nordtal.s2.networkcontrol.db.AccessPool;
import eu.nordtal.s2.networkcontrol.gate.ExpiryWatch;
import eu.nordtal.s2.networkcontrol.gate.FallbackCache;
import eu.nordtal.s2.networkcontrol.gate.GateMessages;
import eu.nordtal.s2.networkcontrol.gate.LoginGate;
import eu.nordtal.s2.networkcontrol.gate.LoginRoster;
import eu.nordtal.s2.networkcontrol.gate.MisconfiguredGate;
import eu.nordtal.s2.networkcontrol.launch.LaunchCountdown;
import eu.nordtal.s2.networkcontrol.pack.PackMessages;
import eu.nordtal.s2.networkcontrol.pack.PackOffer;
import eu.nordtal.s2.networkcontrol.pack.PackStation;
import eu.nordtal.s2.networkcontrol.pack.WaitingBook;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.network.NetworkCommands;
import eu.nordtal.s2.commands.network.NetworkEffects;
import eu.nordtal.s2.commands.phase.PhaseCommands;
import eu.nordtal.s2.commands.phase.PhaseEffects;
import eu.nordtal.s2.commands.remote.CommandInbox;
import eu.nordtal.s2.common.command.CommandRequests;
import eu.nordtal.s2.common.phase.SeasonDates;
import eu.nordtal.s2.networkcontrol.command.ProxyNetworkEffects;
import eu.nordtal.s2.networkcontrol.command.VelocityCommands;
import eu.nordtal.s2.networkcontrol.phase.ProxyPhaseEffects;
import eu.nordtal.s2.common.notify.Channels;
import eu.nordtal.s2.common.notify.NotificationListener;
import eu.nordtal.s2.common.notify.PostgresNotifications;
import eu.nordtal.s2.networkcontrol.phase.PhaseWatch;
import eu.nordtal.s2.networkcontrol.ping.NetworkPing;
import eu.nordtal.s2.networkcontrol.ping.SnapshotStore;
import eu.nordtal.s2.networkcontrol.playtime.PlaytimeStore;
import eu.nordtal.s2.networkcontrol.playtime.PlaytimeWriter;
import eu.nordtal.s2.networkcontrol.routing.PhaseRouting;
import eu.nordtal.s2.networkcontrol.routing.PhaseServers;
import eu.nordtal.s2.networkcontrol.routing.PlayerRouter;
import eu.nordtal.s2.networkcontrol.update.RestartWatch;

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
 * <p>What is wired up here:
 *
 * <ul>
 *   <li>{@link LoginGate} - the phase-aware login decision, one database round trip carrying both
 *       the access state and the {@link SeasonPhase} (docs/season-phases.md).</li>
 *   <li>{@link PhaseWatch} + a {@link NotificationListener} - the 30-second poll <b>and</b> a dedicated
 *       {@code LISTEN nordtal_phase} connection outside the pool. The poll is the guarantee; the
 *       listener only makes a switch feel instant.</li>
 *   <li>{@link PhaseCommand} - the emergency {@code /phase}, authorised by
 *       {@code discord_user.admin} through {@link LoginRoster}.</li>
 *   <li>{@link PlaytimeWriter} - {@code player_playtime}, written on disconnect and periodically
 *       in between (docs/smp.md#prestige--a-crest-earned-by-time).</li>
 *   <li>{@link MisconfiguredGate} - the fail-closed handler, below.</li>
 *   <li>{@link PlayerRouter} - the limbo-first login route and the phase-change re-route
 *       (docs/season-phases.md#routing).</li>
 *   <li>{@link PackStation} - the forced resource-pack offer, the {@code nordtal:limbo} channel and
 *       the release out of the waiting room (docs/architecture.md#the-login-path-end-to-end).</li>
 * </ul>
 *
 * <p><b>Configuration failure fails closed</b> (docs/architecture.md#failing-closed-on-a-bad-config,
 * settled 2026-08-31, implemented here). A bad {@code database.yml} or {@code gate.yml} used to be
 * logged loudly while the proxy kept running and kept accepting logins <em>un-gated</em>; now it
 * registers a {@code LoginEvent} handler that refuses <em>everybody</em>. Velocity has no
 * per-plugin disable, which is what the old behaviour was justified with - but that handler is the
 * disable, built by hand. Admins are not exempted and cannot be: the admin flag lives in the
 * database that a bad {@code database.yml} cannot reach.
 *
 * <p><b>The login path is complete since 2026-09-01.</b> {@link PlayerRouter} sends every admitted
 * login to {@code limbo} whatever the phase, {@link PackStation} offers the resource pack there and
 * releases the player onto the phase's backend once the pack is applied, and a phase change moves
 * everybody - disconnecting a player a switch to {@code SMP} catches without access, and leaving a
 * player still in the waiting room to the pack station rather than connecting them without a pack.
 * The three parts that used to be missing are the {@code pack.yml} config, the
 * {@code nordtal:limbo} plugin-message channel and the {@code limbo} plugin at the other end of it.
 */
@Plugin(
        id = "network-control",
        name = "network-control",
        version = "${version}",
        description = "Season 2 phase control and backend routing.",
        url = "https://nordtal.eu",
        authors = {"nordtal"}
)
public final class NetworkControlPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private HikariDataSource pool;
    private AccessDirectory access;
    private NotificationListener phaseListener;

    /**
     * Commands another process asked this one to run.
     *
     * <p>A field because the notification listener is built before it and refers to it: the listener
     * carries {@code nordtal_command} alongside the phase and admin channels, on one connection.</p>
     */
    private volatile CommandInbox commandInbox;
    /** {@code :commands}' bundle as the inbox renders it - a second view of the same files. */
    private Messages sharedMessages;
    private PlaytimeWriter playtime;
    private com.velocitypowered.api.scheduler.ScheduledTask heartbeat;

    @Inject
    public NetworkControlPlugin(final ProxyServer proxy, final Logger logger,
                                @DataDirectory final Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(final ProxyInitializeEvent event) {
        logger.info("network-control enabled, {} backends registered", proxy.getAllServers().size());

        try {
            // Inside the try, and that is the whole point of this block. Messages.load creates the
            // override directory and writes a README into it - so on a read-only or full volume it
            // throws an UncheckedIOException, and it used to throw it OUT of this method, before
            // failClosed could run. The result was the one state this proxy must never be in: up,
            // accepting logins, with neither LoginGate nor MisconfiguredGate registered. Found by
            // review, 2026-09-04; the deny-all gate exists precisely because Velocity has no
            // per-plugin disable, and it cannot deny anything it was never registered for.
            // Two roots: the shared bundle of :commands underneath this module's own. Every string
            // /phase says is declared with the command rather than here, because the bot says the
            // same ones - see Messages.load(ClassLoader, List, Path, Locale...). This module's own
            // keys win on a collision, which is what lets the proxy reword a shared line for chat.
            final Messages messages = Messages.load(getClass().getClassLoader(),
                    List.of("messages/commands", "messages/network-control"),
                    dataDirectory.resolve("messages"), Locale.ENGLISH, Locale.GERMAN);
            messages.unknownOverrideKeys().forEach(key -> logger.warn(
                    "the message override names {}, which no bundle declares - it is stored and"
                            + " never used; check the spelling", key));

            start(Configs.database(dataDirectory, logger).get(),
                    Configs.gate(dataDirectory, logger).get(),
                    Configs.pack(dataDirectory, logger).get(),
                    Configs.network(dataDirectory, logger).get(),
                    messages);
        } catch (final ConfigException | RuntimeException failure) {
            failClosed(failure);
        }
    }

    private void start(final DatabaseSpec databaseConfig, final GateSpec gateConfig,
                       final PackSpec packConfig, final NetworkSpec networkConfig,
                       final Messages messages) {
        // :commands' bundle on its own, with the operator's override on top of it, for the command
        // inbox to render remote answers with. One root and not two, because the layered bundle
        // above lets THIS module's keys win and this module's keys are allowed MiniMessage, which
        // a Discord admin would read as a literal <green>. /network reload moves both.
        this.sharedMessages = Messages.load(getClass().getClassLoader(), "messages/commands",
                dataDirectory.resolve("messages"), Locale.ENGLISH, Locale.GERMAN);
        this.pool = AccessPool.open(databaseConfig);
        this.access = AccessDirectory.using(pool);

        final PhaseDirectory phases = PhaseDirectory.using(pool);
        final GateMessages gateMessages = new GateMessages(messages, gateConfig);
        final FallbackCache fallback = new FallbackCache(Duration.ofMinutes(gateConfig.fallbackCacheWindowMinutes()));
        final LoginRoster roster = new LoginRoster();

        // ------------------------------------------------------------ the phase: poll and listen

        // PlayerRouter is the phase-change listener, but it needs the watch it listens to (for the
        // login-time phase), so the reference is filled in immediately after the watch exists. The
        // watch never calls its listener from the constructor, only from refresh().
        final PhaseRouting routing = new PhaseRouting(PhaseServers.from(gateConfig));
        final AtomicReference<PlayerRouter> routerRef = new AtomicReference<>();
        final PhaseWatch phaseWatch = new PhaseWatch(phases, logger, (previous, current) -> {
            final PlayerRouter router = routerRef.get();
            if (router != null) {
                router.onPhaseChanged(previous, current);
            }
        });

        // ------------------------------------------------------------ the pack station

        final PackMessages packMessages = new PackMessages(messages);
        final PackOffer offer = packConfig.enabled()
                ? new PackOffer(proxy, packConfig, packMessages)
                : null;
        if (offer == null) {
            logger.warn("pack.yml#enabled is false: NO RESOURCE PACK IS OFFERED. Players still pass "
                    + "through '{}', but every glyph in the tab list, the nametags, the boards and "
                    + "the HUD will render as a missing-glyph box.", gateConfig.serverLimbo());
        } else {
            logger.info("Offering the resource pack from {} (sha1 {}, forced: {})", packConfig.url(),
                    packConfig.sha1(), packConfig.force());
        }

        final WaitingBook book = new WaitingBook(offer != null,
                Duration.ofSeconds(packConfig.applyTimeoutSeconds()),
                Duration.ofSeconds(gateConfig.limboReadyGraceSeconds()), Clock.systemUTC());
        final PackStation packs = new PackStation(proxy, logger, routing, phaseWatch, roster,
                packMessages, packConfig, offer, book);
        packs.registerChannel();

        final PlayerRouter router = new PlayerRouter(this, proxy, logger, access, routing, phaseWatch,
                roster, fallback, gateMessages, packs);
        routerRef.set(router);
        packs.onRelease(router::releaseFromLimbo);
        proxy.getEventManager().register(this, router);
        proxy.getEventManager().register(this, packs);

        final Duration sweepInterval = Duration.ofSeconds(gateConfig.limboSweepIntervalSeconds());
        proxy.getScheduler().buildTask(this, packs::sweep)
                .delay(sweepInterval)
                .repeat(sweepInterval)
                .schedule();

        // Read once, before the first player can arrive, so the proxy never runs on the
        // never-read-it MAINTENANCE fallback longer than it has to.
        phaseWatch.refresh();

        final Duration pollInterval = Duration.ofSeconds(gateConfig.phasePollIntervalSeconds());

        // The admin roster rides the same two signals as the phase - the poll and the LISTEN - for
        // the same reason: LoginRoster is filled at login and was never touched again, so an admin
        // who lost the role in Discord kept /phase and /smp until they disconnected. An emergency
        // revocation is precisely the case where that is the wrong direction.
        //
        // The whole set, re-derived: a lost notification then costs latency and not correctness,
        // and the poll needs no bookkeeping to catch up on.
        final Runnable refreshAdmins = () -> {
            final int changed = roster.refreshAdmins(access.admins());
            if (changed > 0) {
                logger.info("The admin flag changed for {} connected player(s)", changed);
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
            // One connection, two channels, both refreshes on every signal - the arrangement
            // eu.nordtal.s2.common.notify carries the reasoning for. The loop itself moved into
            // :common on 2026-09-04 so the three Paper backends could stop being written a fourth
            // time; this is the same code it always was, with the channel names now next to the SQL
            // that emits them.
            this.phaseListener = new NotificationListener(
                    PostgresNotifications.connector(databaseConfig.jdbcUrl(),
                            databaseConfig.username(), databaseConfig.password(),
                            databaseConfig.queryTimeoutSeconds(),
                            "network-control-notification-listener",
                            java.util.List.of(Channels.PHASE, Channels.ADMIN, Channels.COMMAND)),
                    "network-control-phase-listener",
                    java.util.List.of(
                            new NotificationListener.Refresh("the season phase", phaseWatch::refresh),
                            new NotificationListener.Refresh("the admin roster", refreshAdmins),
                            // One connection carrying three channels. The listener never inspects
                            // which one woke it and runs every refresh on every signal, which is
                            // what makes sharing strictly cheaper than not.
                            new NotificationListener.Refresh("the command inbox", () -> {
                                // Null until the command layer is built, ninety lines further down,
                                // and the listener's own thread calls every refresh the moment it
                                // connects - so the first one lands before this field is assigned.
                                // The five-second poll picks up anything missed in that window.
                                final CommandInbox inbox = commandInbox;
                                if (inbox != null) {
                                    inbox.drain();
                                }
                            })),
                    logger, pollInterval);
            phaseListener.start();
        } else {
            logger.info("The {} and {} LISTEN connection is disabled; the {}s poll is the only path "
                    + "a phase switch or an admin change travels",
                    Channels.PHASE, Channels.ADMIN, pollInterval.toSeconds());
        }

        // ------------------------------------------------------------ the gate

        final LoginGate loginGate = new LoginGate(logger, proxy, access, fallback, roster, gateMessages,
                gateConfig, networkConfig, Clock.systemUTC());
        final ExpiryWatch expiryWatch = new ExpiryWatch(proxy, logger, access, fallback, gateMessages,
                Duration.ofMinutes(gateConfig.expiryWarningLeadMinutes()));

        proxy.getEventManager().register(this, loginGate);
        proxy.getEventManager().register(this, roster);
        proxy.getEventManager().register(this, expiryWatch);

        proxy.getScheduler().buildTask(this, expiryWatch::check)
                .delay(Duration.ofSeconds(gateConfig.expiryCheckIntervalSeconds()))
                .repeat(Duration.ofSeconds(gateConfig.expiryCheckIntervalSeconds()))
                .schedule();

        // ------------------------------------------------------------ the server browser

        // The MOTD and the advertised limit, both out of network.yml. The snapshot behind the
        // placeholders is refreshed on a timer and never on the ping itself: a ping is
        // unauthenticated and arrives in bursts, so it must not be able to make the proxy query
        // anything.
        final SnapshotStore snapshots = SnapshotStore.using(pool, logger);
        final Duration snapshotInterval = Duration.ofSeconds(networkConfig.snapshotRefreshSeconds());
        snapshots.refresh();
        proxy.getScheduler().buildTask(this, snapshots::refresh)
                .delay(snapshotInterval)
                .repeat(snapshotInterval)
                .schedule();
        proxy.getEventManager().register(this, new NetworkPing(proxy, logger, networkConfig, phaseWatch,
                snapshots, messages, Clock.systemUTC()));

        // ------------------------------------------------------------ play time

        this.playtime = new PlaytimeWriter(PlaytimeStore.using(pool), roster, logger);
        proxy.getEventManager().register(this, playtime);

        final Duration flushInterval = Duration.ofSeconds(gateConfig.playtimeFlushIntervalSeconds());
        proxy.getScheduler().buildTask(this, playtime::flushAll)
                .delay(flushInterval)
                .repeat(flushInterval)
                .schedule();

        // ------------------------------------------------------------ the restart countdown

        // The proxy is the only process that sees every player, so it is the one that warns them.
        // A restart is asked for in Discord or with /smp update restart; both write a row with an
        // absolute instant on it, and this counts towards that instant rather than towards a
        // duration of its own - see docs/updater.md#how-it-is-operated.
        final RestartWatch restartWatch = new RestartWatch(proxy, logger,
                UpdateDirectory.using(pool), roster, messages, Clock.systemUTC());
        proxy.getScheduler().buildTask(this, restartWatch::check)
                .delay(RestartWatch.INTERVAL)
                .repeat(RestartWatch.INTERVAL)
                .schedule();

        // ------------------------------------------------------------ the emergency command

        // Every decision lives in :commands and is shared with the bot; VelocityCommands builds the
        // Brigadier tree, resolves the source, confirms, and prints the usage line when somebody
        // types half a command. The proxy registers only ITS OWN commands: Velocity answers a
        // command it knows before the packet reaches a backend, so a /smp here would shadow the
        // SMP's own and turn a local command into a round trip through a request row.
        final PhaseEffects phaseEffects =
                new ProxyPhaseEffects(this, proxy, logger, phases, phaseWatch);
        final NetworkEffects networkEffects = new ProxyNetworkEffects(
                ProxyNetworkEffects.async(this, proxy), messages, sharedMessages, logger);

        final VelocityCommands tree = new VelocityCommands(proxy, roster, messages);
        PhaseCommands.all().forEach(command -> tree.local(command, phaseEffects));
        NetworkCommands.all().forEach(command -> tree.local(command, networkEffects));
        // "clear" is not guessable and is the only value of this argument that is not a date.
        tree.suggest(PhaseCommands.LAUNCH, "when", () -> List.of(SeasonDates.CLEAR));
        tree.suggest(PhaseCommands.SMP_START, "when", () -> List.of(SeasonDates.CLEAR));

        final CommandManager commands = proxy.getCommandManager();
        tree.build().forEach(command -> commands.register(
                commands.metaBuilder(command).plugin(this).build(), command));

        // The proxy's own inbox: /network reload asked for in Discord arrives as a request row.
        // /phase does not travel - the bot runs it against the database itself, because the row it
        // writes is the state and no process owns it.
        commandInbox = new CommandInbox(Target.PROXY,
                CommandRequests.borrowing(pool),
                // :commands' bundle alone - the layered `messages` would let this module's own
                // bundle win, and that one is allowed MiniMessage, which reaches a Discord admin as
                // a literal <green>. Alone means one root and NOT no overrides: the operator's
                // override directory is on it, and sharedMessages is reloaded by /network reload
                // alongside `messages`, so the same command does not read differently depending on
                // where it was typed.
                sharedMessages,
                eu.nordtal.s2.commands.remote.CommandInbox.AdminCheck.of(
                        access::admins, access::adminMinecraftAccounts),
                (message, failure) -> logger.warn(message, failure));
        NetworkCommands.all().forEach(command -> commandInbox.register(command,
                // Inline: the inbox settles a request row when the command returns, so scheduled
                // effects would write the answer before the command produced it.
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
                pollInterval.toSeconds(), flushInterval.toSeconds(), gateConfig.serverLimbo(),
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
     * <p>It is the last thing {@link #start} does, and {@link #failClosed} does not call it at all.
     * That is the whole point on this service: a proxy whose configuration is broken is <em>up</em>,
     * bound to 25565 and answering pings, while refusing every login there is. "The proxy is up and
     * the gate is off" announced itself nowhere until this marker existed - a port check cannot see
     * it, because the port is exactly what still works.</p>
     */
    private void startHeartbeat() {
        final Readiness readiness = Readiness.onDefaultPath(logger::warn);
        heartbeat = proxy.getScheduler().buildTask(this, readiness::refresh)
                .delay(Duration.ZERO)
                .repeat(Readiness.BEAT)
                .schedule();
    }

    /**
     * The whole of docs/architecture.md#failing-closed-on-a-bad-config' fail-closed rule: nothing else has
     * been registered by the time this runs, so this handler is the only thing that sees a login,
     * and it refuses every one of them.
     */
    private void failClosed(final Exception failure) {
        logger.error("network-control could not start, so NOBODY will be let onto this network. "
                + "Fix the configuration and restart the proxy.");
        logger.error("{}", failure.getMessage(), failure);

        // Its own bundle, from the classpath and with NO override directory. The override layer is
        // one of the things that can be broken here - it is a directory this plugin writes into -
        // so the screen that says "the network is misconfigured" must not depend on it. The cost is
        // that an operator who reworded gate.misconfigured sees the packaged wording on this one
        // path; the alternative is no screen at all, and Velocity letting everybody in.
        try {
            final Messages messages = Messages.load(getClass().getClassLoader(),
                    "messages/network-control", Locale.ENGLISH, Locale.GERMAN);
            proxy.getEventManager().register(this, new MisconfiguredGate(logger, messages));
        } catch (final RuntimeException broken) {
            // The packaged bundle is inside this jar, so reaching here means the jar itself is
            // damaged. There is no screen left to refuse anybody with, and a proxy that cannot
            // refuse must not keep accepting: this is the same rule the three Paper plugins follow
            // with getServer().shutdown(), for the same reason. "The proxy is down" announces
            // itself; "the proxy is up and the gate is off" never does.
            logger.error("network-control cannot even load its own packaged messages, so it cannot "
                    + "put up a refusal screen. Stopping the proxy - that is the only way left to "
                    + "refuse everybody.", broken);
            closeResources();
            proxy.shutdown();
            return;
        }

        // Whatever got as far as being opened before the failure has to go: a half-built plugin
        // holding a connection pool open is worse than one holding nothing.
        closeResources();
    }

    @Subscribe
    public void onProxyShutdown(final ProxyShutdownEvent event) {
        if (playtime != null) {
            // The last slice of every connected session. Without this, a planned restart costs
            // everybody the time since their last periodic flush for no reason at all.
            logger.info("Flushed play time for {} players on shutdown", playtime.flushAll());
        }
        closeResources();
    }

    private void closeResources() {
        // Stops the beat, so a proxy that is going down stops claiming to be up. The marker is
        // deliberately not deleted: going stale is the signal. This runs on the fail-closed path
        // too, where there is nothing to cancel - and nothing to claim either.
        if (heartbeat != null) {
            heartbeat.cancel();
            heartbeat = null;
        }
        if (phaseListener != null) {
            phaseListener.close();
            phaseListener = null;
        }
        // access.close() is a no-op (AccessDirectory.using(...) never owns the pool it is handed) -
        // this proxy built the pool itself with AccessPool and is the one that has to close it.
        if (pool != null) {
            pool.close();
            pool = null;
        }
        access = null;
    }
}
