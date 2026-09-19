package eu.nordtal.s2.proxy.online;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import eu.nordtal.s2.common.online.OnlineDirectory;
import eu.nordtal.s2.common.online.OnlineRoster;
import eu.nordtal.s2.proxy.routing.PhaseServers;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes what the proxy currently sees into {@code online_count}, on a timer (steward/86).
 *
 * <h2>Why the proxy, and not each backend</h2>
 * proxy is the one process that already knows every connection and which backend it is
 * on, without adding anything up itself - {@code ProxyServer.getPlayerCount()} is the network total
 * {@link eu.nordtal.s2.proxy.gate.LoginGate} already enforces against, and
 * {@code RegisteredServer.getPlayersConnected().size()} is what
 * {@link eu.nordtal.s2.proxy.ping.Placeholders} already puts in the MOTD. This class writes
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
 *
 * <h2>How many, and who - one tick, two tables (steward/111)</h2>
 * {@code online_count} is numbers and nothing else, by its own migration's decision, so the names
 * live in {@code online_player} next to it and are written from this same method: one pass over the
 * proxy, both tables, on {@link OnlineDirectory#WRITE_INTERVAL}. A second timer would let the count
 * and the list describe two different moments for no gain at all.
 *
 * <p>The two writes are guarded separately, which is the one place they are not treated as one
 * thing: a roster write that fails must not cost the counts their tick, since a number with no
 * faces is most of what the dashboard shows and faces with no number is none of it.
 */
public final class OnlineWriter {

    private final ProxyServer proxy;
    private final PhaseServers servers;
    private final OnlineDirectory online;
    private final OnlineRoster roster;
    private final Logger logger;

    public OnlineWriter(final ProxyServer proxy, final PhaseServers servers,
                        final OnlineDirectory online, final OnlineRoster roster,
                        final Logger logger) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.online = Objects.requireNonNull(online, "online");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Called from the proxy's scheduler on {@link OnlineDirectory#WRITE_INTERVAL}. */
    public void write() {
        writeCounts();
        writeRoster();
    }

    private void writeCounts() {
        try {
            online.write(OnlineCounts.of(proxy.getPlayerCount(), playersByServer()));
        } catch (final RuntimeException failure) {
            // Nothing is retried here and nothing is cleared: the next tick is the retry, and a
            // slightly stale row beats a dashboard that goes blank over one missed write.
            logger.warn("Could not write online player counts; the service list keeps showing the "
                    + "last numbers it saw", failure);
        }
    }

    private void writeRoster() {
        try {
            roster.replace(present());
        } catch (final RuntimeException failure) {
            // Same rule as above, and the same retry: the next tick. A roster that is one tick
            // behind shows a face too many for ten seconds; the count next to it is unaffected.
            logger.warn("Could not write the online player list; the service list keeps showing "
                    + "the last names it saw", failure);
        }
    }

    /**
     * Everyone the proxy currently has, with the backend they are on where there is one.
     *
     * <p>A player with no current server is included with a {@code null} subject rather than left
     * out: {@code getPlayerCount()} counts them, so dropping them here would make the list one
     * shorter than the number beside it for no reason a reader could see. They are mid-transfer or
     * one step past login, and where they are is the only thing nobody knows yet.
     */
    private List<OnlineRoster.Presence> present() {
        final List<OnlineRoster.Presence> connected = new ArrayList<>();
        for (final Player player : proxy.getAllPlayers()) {
            connected.add(new OnlineRoster.Presence(
                    player.getUniqueId(),
                    player.getUsername(),
                    player.getCurrentServer()
                            .map(server -> server.getServerInfo().getName())
                            .orElse(null)));
        }
        return connected;
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
