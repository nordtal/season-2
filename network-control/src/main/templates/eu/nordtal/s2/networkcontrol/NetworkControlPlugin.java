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
import eu.nordtal.s2.networkcontrol.phase.PhaseCommand;
import eu.nordtal.s2.networkcontrol.phase.PhaseListener;
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
 *   <li>{@link PhaseWatch} + {@link PhaseListener} - the 30-second poll <b>and</b> a dedicated
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
    private PhaseListener phaseListener;
    private PlaytimeWriter playtime;

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

        // Loaded before anything else and off the config path entirely: it is a classpath bundle,
        // so it is the one thing still available when the configuration is what is broken.
        final Messages messages = Messages.load("messages/network-control", Locale.ENGLISH, Locale.GERMAN);

        try {
            start(Configs.database(dataDirectory, logger).get(),
                    Configs.gate(dataDirectory, logger).get(),
                    Configs.pack(dataDirectory, logger).get(),
                    Configs.network(dataDirectory, logger).get(),
                    messages);
        } catch (final ConfigException | RuntimeException failure) {
            failClosed(messages, failure);
        }
    }

    private void start(final DatabaseSpec databaseConfig, final GateSpec gateConfig,
                       final PackSpec packConfig, final NetworkSpec networkConfig,
                       final Messages messages) {
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
            this.phaseListener = new PhaseListener(PhaseListener.postgres(databaseConfig), phaseWatch,
                    refreshAdmins, logger, pollInterval);
            phaseListener.start();
        } else {
            logger.info("The nordtal_phase and nordtal_admin LISTEN connection is disabled; the {}s "
                    + "poll is the only path a phase switch or an admin change travels",
                    pollInterval.toSeconds());
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

        final PhaseCommand phaseCommand = new PhaseCommand(this, proxy, logger, phases, phaseWatch,
                roster, messages);
        final CommandManager commands = proxy.getCommandManager();
        commands.register(commands.metaBuilder(PhaseCommand.alias()).plugin(this).build(),
                phaseCommand.build());

        logger.info("Access login gate is up in phase {} (query timeout {}s, fallback cache window "
                        + "{}m, expiry check every {}s, phase poll every {}s, play time flushed every "
                        + "{}s, waiting room '{}' swept every {}s)",
                phaseWatch.lastKnown(), databaseConfig.queryTimeoutSeconds(),
                gateConfig.fallbackCacheWindowMinutes(), gateConfig.expiryCheckIntervalSeconds(),
                pollInterval.toSeconds(), flushInterval.toSeconds(), gateConfig.serverLimbo(),
                sweepInterval.toSeconds());
        logger.info("The network takes {} players and the browser is told so; the Paper backends are "
                        + "configured for {} and refuse nobody. MOTD refreshed every {}s.",
                networkConfig.maxPlayers(), networkConfig.backendLimit(), snapshotInterval.toSeconds());
        if (phaseWatch.lastKnown() == SeasonPhase.PRE_LAUNCH) {
            logger.info("The network has not opened yet: only admins get in, everybody else is shown "
                            + "the countdown ({}).",
                    LaunchCountdown.render(messages, Locale.ENGLISH, phaseWatch.launch().orElse(null),
                            Clock.systemUTC().instant()));
        }
    }

    /**
     * The whole of docs/architecture.md#failing-closed-on-a-bad-config' fail-closed rule: nothing else has
     * been registered by the time this runs, so this handler is the only thing that sees a login,
     * and it refuses every one of them.
     */
    private void failClosed(final Messages messages, final Exception failure) {
        logger.error("network-control could not start, so NOBODY will be let onto this network. "
                + "Fix the configuration and restart the proxy.");
        logger.error("{}", failure.getMessage(), failure);

        proxy.getEventManager().register(this, new MisconfiguredGate(logger, messages));

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
