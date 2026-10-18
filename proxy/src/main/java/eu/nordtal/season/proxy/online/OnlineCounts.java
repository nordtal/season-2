package eu.nordtal.season.proxy.online;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Builds the map for {@code OnlineDirectory#write}. */
public final class OnlineCounts {

    /** The subject of the network total in {@code online_count}. */
    public static final String PROXY = "proxy";

    private OnlineCounts() {}

    /** Returns the counts of the servers and {@value #PROXY} with {@code total}. */
    public static Map<String, Integer> of(final int total, final Map<String, Integer> playersByServer) {
        Objects.requireNonNull(playersByServer, "playersByServer");
        final Map<String, Integer> counts = new LinkedHashMap<>(playersByServer);
        counts.put(PROXY, total);
        return counts;
    }
}
