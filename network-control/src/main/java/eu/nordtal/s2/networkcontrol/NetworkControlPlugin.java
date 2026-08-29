package eu.nordtal.s2.networkcontrol;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.s2.common.SeasonPhase;
import org.slf4j.Logger;

/**
 * Owns the season 2 phase state machine and the routing that follows from it: which backend
 * a player belongs on given the current {@link SeasonPhase}.
 *
 * <p>Scaffold only — no behaviour yet. Routing is meant to go through the SimpleCloud API
 * (server groups, player transfers, availability events) rather than raw Velocity server
 * registration, so that it cannot send a player to a server the cloud has not started.
 */
@Plugin(
        id = "network-control",
        name = "network-control",
        version = "@version@",
        description = "Season 2 phase control and backend routing.",
        url = "https://nordtal.eu",
        authors = {"nordtal"}
)
public final class NetworkControlPlugin {

    private final ProxyServer proxy;
    private final Logger logger;

    @Inject
    public NetworkControlPlugin(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logger.info("network-control enabled, {} backends registered", proxy.getAllServers().size());
    }
}
