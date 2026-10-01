package eu.nordtal.s2.papercommon.world;

import eu.nordtal.s2.settings.DistancesSpec;
import java.util.function.Consumer;

/**
 * A view and a simulation distance in chunks; 0 in either leaves the server's own value.
 *
 * @param view       how far a player sees
 * @param simulation how far around a player the world moves
 */
public record Distances(int view, int simulation) {

    /** Neither distance set: every world keeps what the server runs it with. */
    public static final Distances NONE = new Distances(0, 0);

    /** The smallest distance Paper's {@code World} accepts. */
    static final int MIN = 2;

    /** The largest distance Paper's {@code World} accepts. */
    static final int MAX = 32;

    /**
     * Returns what {@code distances.yml} sets, each value it leaves at 0 taken from {@code defaults}, within 2 to 32.
     *
     * @param warn told about a value outside 2 to 32, with the value used instead
     */
    public static Distances of(final DistancesSpec configured, final Distances defaults, final Consumer<String> warn) {
        return new Distances(
                pick("view-distance", configured.viewDistance(), defaults.view(), warn),
                pick("simulation-distance", configured.simulationDistance(), defaults.simulation(), warn));
    }

    private static int pick(final String key, final int configured, final int fallback, final Consumer<String> warn) {
        final int wanted = configured != 0 ? configured : fallback;
        if (wanted == 0) {
            return 0;
        }
        final int used = Math.clamp(wanted, MIN, MAX);
        if (used != wanted) {
            warn.accept("distances.yml#" + key + " is " + wanted + ", outside the " + MIN + " to " + MAX
                    + " Paper accepts; " + used + " is used");
        }
        return used;
    }
}
