package eu.nordtal.season.proxy.online;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Turns what the proxy currently sees into the map {@code OnlineDirectory#write} expects. */
public final class OnlineCounts {

    /** The proxy's own subject in {@code online_count}: the network total, next to the backends. */
    public static final String PROXY = "proxy";

    private OnlineCounts() {}

    /**
     * The backends' counts plus {@value #PROXY} for {@code total}.
     *
     * A backend that is not registered is absent from {@code playersByServer} and stays absent from the result.
     */
    public static Map<String, Integer> of(final int total, final Map<String, Integer> playersByServer) {
        Objects.requireNonNull(playersByServer, "playersByServer");
        final Map<String, Integer> counts = new LinkedHashMap<>(playersByServer);
        counts.put(PROXY, total);
        return counts;
    }
}
