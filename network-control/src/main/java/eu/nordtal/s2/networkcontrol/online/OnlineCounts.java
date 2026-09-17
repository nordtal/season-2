package eu.nordtal.s2.networkcontrol.online;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Turns what the proxy currently sees into the map {@code OnlineDirectory#write} expects
 * (steward/86).
 *
 * <p>Static and free of every Velocity type on purpose, the same reason {@code BackendKick.decide}
 * and {@code PhaseRouting.decide} are: the interesting arithmetic is one line, and a test for it
 * should not need to fake {@code ProxyServer} or {@code RegisteredServer} to exercise it. {@link
 * OnlineWriter} is the thin, untested-on-its-own layer that reads the real proxy and calls this.
 */
public final class OnlineCounts {

    /**
     * The proxy's own subject in {@code online_count} - the network total, next to the three
     * backends. Not one of {@link eu.nordtal.s2.networkcontrol.routing.PhaseServers}' names because
     * it names this process, not a phase's destination, and is never configurable the way they are.
     */
    public static final String NETWORK_CONTROL = "network-control";

    private OnlineCounts() {
    }

    /**
     * @param total           {@code ProxyServer.getPlayerCount()} - every player on the network,
     *                        whichever backend they are on
     * @param playersByServer one entry per backend this proxy currently has registered, name to
     *                        {@code RegisteredServer.getPlayersConnected().size()}. A backend simply
     *                        not registered right now (dev stack without hunger-games, say) is left
     *                        out of this map entirely rather than guessed at zero - and stays out of
     *                        the result, which is exactly what leaves it absent from
     *                        {@code online_count} until it is
     * @return the map to hand {@code OnlineDirectory#write}: every entry of {@code playersByServer}
     *         plus {@value #NETWORK_CONTROL} for {@code total}
     */
    public static Map<String, Integer> of(final int total, final Map<String, Integer> playersByServer) {
        Objects.requireNonNull(playersByServer, "playersByServer");
        final Map<String, Integer> counts = new LinkedHashMap<>(playersByServer);
        counts.put(NETWORK_CONTROL, total);
        return counts;
    }
}
