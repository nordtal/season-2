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
import eu.nordtal.s2.networkcontrol.config.Configs;
import eu.nordtal.s2.networkcontrol.config.DatabaseSpec;
import eu.nordtal.s2.networkcontrol.config.GateSpec;
import eu.nordtal.s2.networkcontrol.db.AccessPool;
import eu.nordtal.s2.networkcontrol.gate.ExpiryWatch;
import eu.nordtal.s2.networkcontrol.gate.FallbackCache;
import eu.nordtal.s2.networkcontrol.gate.GateMessages;
import eu.nordtal.s2.networkcontrol.gate.LoginGate;
import eu.nordtal.s2.networkcontrol.gate.LoginRoster;
import eu.nordtal.s2.networkcontrol.gate.MisconfiguredGate;
import eu.nordtal.s2.networkcontrol.phase.PhaseCommand;
import eu.nordtal.s2.networkcontrol.phase.PhaseListener;
import eu.nordtal.s2.networkcontrol.phase.PhaseWatch;
import eu.nordtal.s2.networkcontrol.playtime.PlaytimeStore;
import eu.nordtal.s2.networkcontrol.playtime.PlaytimeWriter;
import eu.nordtal.s2.networkcontrol.routing.PhaseRouting;
import eu.nordtal.s2.networkcontrol.routing.PhaseServers;
import eu.nordtal.s2.networkcontrol.routing.PlayerRouter;

import org.slf4j.Logger;

import java.nio.file.Path;
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
 *   <li>{@link PlayerRouter} - the phase-change re-route and the {@code MAINTENANCE} hop into
 *       {@code limbo} (docs/season-phases.md#routing).</li>
 * </ul>
 *
 * <p><b>Configuration failure fails closed</b> (docs/operations.md#configuration-and-secrets,
 * settled 2026-08-31, implemented here). A bad {@code database.yml} or {@code gate.yml} used to be
 * logged loudly while the proxy kept running and kept accepting logins <em>un-gated</em>; now it
 * registers a {@code LoginEvent} handler that refuses <em>everybody</em>. Velocity has no
 * per-plugin disable, which is what the old behaviour was justified with - but that handler is the
 * disable, built by hand. Admins are not exempted and cannot be: the admin flag lives in the
 * database that a bad {@code database.yml} cannot reach.
 *
 * <p><b>Routing is half built, on purpose.</b> {@link PlayerRouter} carries out the two rules that
 * do not depend on the {@code limbo} module existing as code: a phase change moves connected
 * players to the new phase's backend (disconnecting a player a switch to {@code SMP} catches without
 * access), and a login during {@code MAINTENANCE} is put in the waiting room instead of being
 * refused. What is <em>not</em> here is docs/architecture.md's "every login lands on {@code limbo}
 * first, whatever the phase": that is the resource-pack station, and it needs a {@code limbo} that
 * applies a pack and answers on a {@code nordtal:} plugin-message channel. Neither exists, so
 * non-maintenance logins keep {@code velocity.toml}'s own {@code try} list and the {@code limbo}
 * session builds the rest.
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
                    messages);
        } catch (final ConfigException | RuntimeException failure) {
            failClosed(messages, failure);
        }
    }

    private void start(final DatabaseSpec databaseConfig, final GateSpec gateConfig,
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

        final PlayerRouter router = new PlayerRouter(this, proxy, logger, access, routing, phaseWatch,
                roster, fallback, gateMessages);
        routerRef.set(router);
        proxy.getEventManager().register(this, router);

        // Read once, before the first player can arrive, so the proxy never runs on the
        // never-read-it MAINTENANCE fallback longer than it has to.
        phaseWatch.refresh();

        final Duration pollInterval = Duration.ofSeconds(gateConfig.phasePollIntervalSeconds());
        proxy.getScheduler().buildTask(this, phaseWatch::refresh)
                .delay(pollInterval)
                .repeat(pollInterval)
                .schedule();

        if (gateConfig.phaseListenEnabled()) {
            this.phaseListener = new PhaseListener(PhaseListener.postgres(databaseConfig), phaseWatch,
                    logger, pollInterval);
            phaseListener.start();
        } else {
            logger.info("The nordtal_phase LISTEN connection is disabled; the {}s poll is the only "
                    + "path a phase switch travels", pollInterval.toSeconds());
        }

        // ------------------------------------------------------------ the gate

        final LoginGate loginGate = new LoginGate(logger, access, fallback, roster, gateMessages, gateConfig);
        final ExpiryWatch expiryWatch = new ExpiryWatch(proxy, logger, access, fallback, gateMessages,
                Duration.ofMinutes(gateConfig.expiryWarningLeadMinutes()));

        proxy.getEventManager().register(this, loginGate);
        proxy.getEventManager().register(this, roster);
        proxy.getEventManager().register(this, expiryWatch);

        proxy.getScheduler().buildTask(this, expiryWatch::check)
                .delay(Duration.ofSeconds(gateConfig.expiryCheckIntervalSeconds()))
                .repeat(Duration.ofSeconds(gateConfig.expiryCheckIntervalSeconds()))
                .schedule();

        // ------------------------------------------------------------ play time

        this.playtime = new PlaytimeWriter(PlaytimeStore.using(pool), roster, logger);
        proxy.getEventManager().register(this, playtime);

        final Duration flushInterval = Duration.ofSeconds(gateConfig.playtimeFlushIntervalSeconds());
        proxy.getScheduler().buildTask(this, playtime::flushAll)
                .delay(flushInterval)
                .repeat(flushInterval)
                .schedule();

        // ------------------------------------------------------------ the emergency command

        final PhaseCommand phaseCommand = new PhaseCommand(this, proxy, logger, phases, phaseWatch,
                roster, messages);
        final CommandManager commands = proxy.getCommandManager();
        commands.register(commands.metaBuilder(PhaseCommand.alias()).plugin(this).build(),
                phaseCommand.build());

        logger.info("Access login gate is up in phase {} (query timeout {}s, fallback cache window "
                        + "{}m, expiry check every {}s, phase poll every {}s, play time flushed every {}s)",
                phaseWatch.lastKnown(), databaseConfig.queryTimeoutSeconds(),
                gateConfig.fallbackCacheWindowMinutes(), gateConfig.expiryCheckIntervalSeconds(),
                pollInterval.toSeconds(), flushInterval.toSeconds());
    }

    /**
     * The whole of docs/operations.md#configuration-and-secrets' fail-closed rule: nothing else has
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
