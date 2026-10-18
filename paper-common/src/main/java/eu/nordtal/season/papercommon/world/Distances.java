package eu.nordtal.season.papercommon.world;

import eu.nordtal.season.settings.DistancesSpec;
import eu.nordtal.season.settings.Group;
import java.util.function.Consumer;

/** A view and a simulation distance in chunks. 0 leaves the server's value. */
public record Distances(int view, int simulation) {

    public static final Distances NONE = new Distances(0, 0);

    static final int MIN = 2;

    static final int MAX = 32;

    public static Group<DistancesSpec> group(final Distances defaults) {
        return Group.of("distances", DistancesSpec.class)
                .whileRunning()
                .defaulting("view-distance", defaults.view())
                .defaulting("simulation-distance", defaults.simulation());
    }

    /**
     * Returns the distances set, limited to 2 to 32.
     * A value outside is reported to {@code warn} with the value used.
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
