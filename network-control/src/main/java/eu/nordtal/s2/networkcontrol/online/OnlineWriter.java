package eu.nordtal.s2.networkcontrol.online;

import com.velocitypowered.api.proxy.ProxyServer;

import eu.nordtal.s2.common.online.OnlineDirectory;
import eu.nordtal.s2.networkcontrol.routing.PhaseServers;

import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes what the proxy currently sees into {@code online_count}, on a timer (steward/86).
 *
 * <h2>Why the proxy, and not each backend</h2>
 * network-control is the one process that already knows every connection and which backend it is
 * on, without adding anything up itself - {@code ProxyServer.getPlayerCount()} is the network total
 * {@link eu.nordtal.s2.networkcontrol.gate.LoginGate} already enforces against, and
 * {@code RegisteredServer.getPlayersConnected().size()} is what
 * {@link eu.nordtal.s2.networkcontrol.ping.Placeholders} already puts in the MOTD. This class writes
 * the same two calls out to a table instead of a ping response, so steward-worker - which has no
 * Velocity API and no reason to have one - can read them.
 *
 * <h2>A failed write is logged and dropped, never retried out of turn</h2>
 * The next tick is the retry, the same rule {@code SnapshotStore#refresh} and
 * {@code PlaytimeWriter#flush} both already follow: a database hiccup costs one row's freshness, and
 * nothing here should try harder than the schedule that calls it.
 *
 * <p>The arithmetic itself is {@link OnlineCounts#of}, kept static and free of every Velocity type
 * so it can be tested without a fake {@code ProxyServer}; this class is the thin, unavoidably
 * Velocity-shaped layer around it.
 */
public final class OnlineWriter {

    private final ProxyServer proxy;
    private final PhaseServers servers;
    private final OnlineDirectory online;
    private final Logger logger;

    public OnlineWriter(final ProxyServer proxy, final PhaseServers servers,
                        final OnlineDirectory online, final Logger logger) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.online = Objects.requireNonNull(online, "online");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Called from the proxy's scheduler on {@link OnlineDirectory#WRITE_INTERVAL}. */
    public void write() {
        try {
            online.write(OnlineCounts.of(proxy.getPlayerCount(), playersByServer()));
        } catch (final RuntimeException failure) {
            // Nothing is retried here and nothing is cleared: the next tick is the retry, and a
            // slightly stale row beats a dashboard that goes blank over one missed write.
            logger.warn("Could not write online player counts; the service list keeps showing the "
                    + "last numbers it saw", failure);
        }
    }

    private Map<String, Integer> playersByServer() {
        final Map<String, Integer> byServer = new LinkedHashMap<>();
        for (final String name : List.of(servers.smp(), servers.hungerGames(), servers.limbo())) {
            proxy.getServer(name).ifPresent(
                    server -> byServer.put(name, server.getPlayersConnected().size()));
        }
        return byServer;
    }
}
