package eu.nordtal.season.smp.rail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Stops a driven cart before each junction set the wrong way and switches it once the cart stands.
 * The cart sets off after a short pause, so the rider sees the switch. A junction set right is passed without a
 * stop, and none is switched under a moving cart. Distances are along the cart's {@link RidePath}, in blocks.
 */
final class JunctionStops {

    /** How far before a junction set the wrong way the cart stops. */
    static final double BEFORE = 2;

    /** How many ticks a cart stands after a switch before it sets off. */
    static final int PAUSE = 10;

    /** How far beyond its stop a standing cart switches the next junctions set the wrong way along with it. */
    static final double TOGETHER = 8;

    /** How close to its stop a cart counts as standing there, against rounding. */
    private static final double AT = 1e-6;

    /** The junctions as they lie in the world. */
    interface Junctions {

        /** Whether the junction {@code rail} is read, is set otherwise than {@code wanted} and can take that shape. */
        boolean wrong(RailPos rail, RailShape wanted);

        /** Sets the junction {@code rail} to {@code wanted}. */
        void set(RailPos rail, RailShape wanted);
    }

    private int pause;

    /**
     * Returns the distance up to which the cart may ride this tick, and switches junctions once it stands.
     * It stops {@link #BEFORE} the nearest junction set the wrong way, or where it is when closer, then waits.
     *
     * @param switches the shape the route wants of each junction, by its index on the path, in riding order
     * @param speed    the speed the cart had on its last tick; zero for a cart that stands
     * @param reach    the distance up to which the junctions are read
     */
    double limit(
            final RidePath path,
            final Map<Integer, RailShape> switches,
            final double along,
            final double speed,
            final double reach,
            final Junctions junctions) {
        if (pause > 0) {
            pause--;
            return along;
        }
        final int on = path.railAt(along);
        final List<Map.Entry<Integer, RailShape>> wrong = new ArrayList<>();
        for (final Map.Entry<Integer, RailShape> junction : switches.entrySet()) {
            final int index = junction.getKey();
            final boolean passed = index < on || (index == on && speed > 0);
            if (!passed
                    && path.start(index) <= reach
                    && junctions.wrong(path.rails().get(index), junction.getValue())) {
                wrong.add(junction);
            }
        }
        if (wrong.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        wrong.sort(Map.Entry.comparingByKey(Comparator.naturalOrder()));
        final double stop = path.start(wrong.getFirst().getKey()) - BEFORE;
        if (speed > 0 || along < stop - AT) {
            return Math.max(along, stop);
        }
        for (final Map.Entry<Integer, RailShape> junction : wrong) {
            if (path.start(junction.getKey()) - BEFORE <= stop + TOGETHER) {
                junctions.set(path.rails().get(junction.getKey()), junction.getValue());
            }
        }
        pause = PAUSE;
        return along;
    }
}
