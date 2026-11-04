package eu.nordtal.season.smp.rail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** The junctions as they lie in the world, and which cart holds each. */
    interface Junctions {

        /** Whether the junction {@code rail} is loaded, set otherwise than {@code wanted} and can take it. */
        boolean wrong(RailPos rail, RailShape wanted);

        /** Holds the junction {@code rail} for this cart, and returns false while another cart holds it. */
        boolean claim(RailPos rail);

        /** Lets go of the junction {@code rail}. */
        void release(RailPos rail);

        /** Sets the junction {@code rail} to {@code wanted}. */
        void set(RailPos rail, RailShape wanted);
    }

    private final Set<Integer> claimed = new HashSet<>();
    private int pause;

    /**
     * Returns the distance up to which the cart may ride this tick, and switches junctions once it stands.
     * It stops {@link #BEFORE} the nearest junction set the wrong way that it can still brake for, then waits.
     *
     * @param switches the shape the route wants of each junction, by its index on the path
     * @param speed    the speed the cart had on its last tick; zero for a cart that stands
     * @param reach    the distance up to which the junctions are read
     */
    double limit(
            final RidePath path,
            final Map<Integer, RailShape> switches,
            final double along,
            final double speed,
            final double reach,
            final RidePath.Motion motion,
            final Junctions junctions) {
        final int on = path.railAt(along);
        for (final Iterator<Integer> held = claimed.iterator(); held.hasNext(); ) {
            final int index = held.next();
            if (index < on) {
                held.remove();
                junctions.release(path.rails().get(index));
            }
        }
        // A cart holds every junction it could no longer stop before, so no other cart switches it under it.
        final double committed = along + speed + motion.stopping(speed) + BEFORE;
        for (final int index : switches.keySet()) {
            if (index >= on && path.start(index) <= committed) {
                take(path, index, junctions);
            }
        }
        if (pause > 0) {
            pause--;
            return along;
        }
        final List<Map.Entry<Integer, RailShape>> wrong = new ArrayList<>();
        for (final Map.Entry<Integer, RailShape> junction : switches.entrySet()) {
            final int index = junction.getKey();
            final double left = path.start(index) - BEFORE - along;
            // A moving cart rides over a junction it can no longer brake for, rather than brake harder.
            final boolean passing =
                    index < on || (speed > 0 && left < motion.stopping(speed) - motion.acceleration() / 2);
            if (!passing
                    && path.start(index) <= reach
                    && junctions.wrong(path.rails().get(index), junction.getValue())) {
                wrong.add(junction);
            }
        }
        if (wrong.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        wrong.sort(Map.Entry.comparingByKey(Comparator.naturalOrder()));
        final int first = wrong.getFirst().getKey();
        final double stop = path.start(first) - BEFORE;
        if (speed > 0 || along < stop - AT || !take(path, first, junctions)) {
            // A junction another cart holds is waited for, so two carts never switch it back and forth.
            return Math.max(along, stop);
        }
        for (final Map.Entry<Integer, RailShape> junction : wrong) {
            final int index = junction.getKey();
            if (path.start(index) - BEFORE <= stop + TOGETHER && take(path, index, junctions)) {
                junctions.set(path.rails().get(index), junction.getValue());
            }
        }
        pause = PAUSE;
        return along;
    }

    private boolean take(final RidePath path, final int index, final Junctions junctions) {
        if (claimed.contains(index) || junctions.claim(path.rails().get(index))) {
            claimed.add(index);
            return true;
        }
        return false;
    }
}
