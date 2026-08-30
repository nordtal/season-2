package eu.nordtal.s2.networkcontrol;

import com.google.inject.Inject;
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
import eu.nordtal.s2.networkcontrol.config.Configs;
import eu.nordtal.s2.networkcontrol.config.DatabaseSpec;
import eu.nordtal.s2.networkcontrol.config.GateSpec;
import eu.nordtal.s2.networkcontrol.db.AccessPool;
import eu.nordtal.s2.networkcontrol.gate.ExpiryWatch;
import eu.nordtal.s2.networkcontrol.gate.FallbackCache;
import eu.nordtal.s2.networkcontrol.gate.GateMessages;
import eu.nordtal.s2.networkcontrol.gate.LoginGate;

import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * Owns the season 2 phase state machine and the routing that follows from it, plus - since stage
 * C - the access login gate: {@link LoginGate} decides whether a login may proceed at all, before
 * any {@link SeasonPhase}-driven backend routing gets a say.
 *
 * <p>Backend routing itself is still a scaffold: it is meant to go through the SimpleCloud API
 * (server groups, player transfers, availability events) rather than raw Velocity server
 * registration, so that it cannot send a player to a server the cloud has not started.
 *
 * <p><b>Configuration failure does not take the proxy down.</b> Unlike a Paper plugin, Velocity
 * has no per-plugin disable; a bad {@code database.yml} or {@code gate.yml} is logged loudly here
 * and the gate is simply never registered, so the proxy keeps running (and keeps accepting
 * logins un-gated) rather than the whole process refusing to start. That trade is deliberate for
 * a proxy - see the stage C completion report for why it is flagged rather than assumed.
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

        final DatabaseSpec databaseConfig;
        final GateSpec gateConfig;
        try {
            databaseConfig = Configs.database(dataDirectory, logger).get();
            gateConfig = Configs.gate(dataDirectory, logger).get();
        } catch (final ConfigException exception) {
            logger.error("The access login gate is NOT starting because its configuration could "
                    + "not be read. Logins are not being gated at all until this is fixed and the "
                    + "proxy is restarted.");
            logger.error("{}", exception.getMessage());
            return;
        }

        this.pool = AccessPool.open(databaseConfig);
        this.access = AccessDirectory.using(pool);

        final Messages messages = Messages.load("messages/network-control", Locale.ENGLISH, Locale.GERMAN);
        final GateMessages gateMessages = new GateMessages(messages, gateConfig);
        final FallbackCache fallback = new FallbackCache(Duration.ofMinutes(gateConfig.fallbackCacheWindowMinutes()));

        final LoginGate loginGate = new LoginGate(logger, access, fallback, gateMessages, gateConfig);
        final ExpiryWatch expiryWatch = new ExpiryWatch(proxy, logger, access, fallback, gateMessages,
                Duration.ofMinutes(gateConfig.expiryWarningLeadMinutes()));

        proxy.getEventManager().register(this, loginGate);
        proxy.getEventManager().register(this, expiryWatch);

        proxy.getScheduler().buildTask(this, expiryWatch::check)
                .delay(Duration.ofSeconds(gateConfig.expiryCheckIntervalSeconds()))
                .repeat(Duration.ofSeconds(gateConfig.expiryCheckIntervalSeconds()))
                .schedule();

        logger.info("Access login gate is up (query timeout {}s, fallback cache window {}m, "
                        + "expiry check every {}s)", databaseConfig.queryTimeoutSeconds(),
                gateConfig.fallbackCacheWindowMinutes(), gateConfig.expiryCheckIntervalSeconds());
    }

    @Subscribe
    public void onProxyShutdown(final ProxyShutdownEvent event) {
        // access.close() is a no-op here (AccessDirectory.using(...) never owns the pool it is
        // handed) - this proxy built the pool itself with AccessPool and is the one that has to
        // close it.
        if (pool != null) {
            pool.close();
        }
    }
}
