package eu.nordtal.s2.papercommon.world;

import eu.nordtal.s2.settings.DistancesSpec;
import eu.nordtal.s2.settings.Group;
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

    /** Returns the distances group of a server whose own distances are {@code defaults}, which Steward then shows. */
    public static Group<DistancesSpec> group(final Distances defaults) {
        return Group.of("distances", DistancesSpec.class)
                .whileRunning()
                .defaulting("view-distance", defaults.view())
                .defaulting("simulation-distance", defaults.simulation());
    }

    /**
     * Returns the distances set, within 2 to 32; 0 stays 0.
     *
     * @param warn told about a value outside 2 to 32, with the value used instead
     */
    public static Distances of(final DistancesSpec configured, final Consumer<String> warn) {
        return new Distances(
                pick("view-distance", configured.viewDistance(), warn),
                pick("simulation-distance", configured.simulationDistance(), warn));
    }

    private static int pick(final String key, final int wanted, final Consumer<String> warn) {
        if (wanted == 0) {
            return 0;
        }
        final int used = Math.clamp(wanted, MIN, MAX);
        if (used != wanted) {
            warn.accept("distances " + key + " is " + wanted + ", outside the " + MIN + " to " + MAX
                    + " Paper accepts; " + used + " is used");
        }
        return used;
    }
}
