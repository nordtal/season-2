package eu.nordtal.season.smp.rail;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Crawls a driven cart up to each junction set the wrong way and switches it in front of the cart.
 * A contested junction, or one whose switch must still wait, stops the cart two blocks before it.
 * Distances are along the cart's {@link RidePath}, in blocks.
 */
final class JunctionStops {

    /** How many ticks must pass between two switches. */
    static final int SWITCH_INTERVAL = 10;

    /** How far before a junction a cart stops when it cannot switch it yet. */
    static final double STOP_BEFORE = 2;

    /** How close to a switch point a cart counts as reaching it, against rounding. */
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

    /** Where the cart may ride, and the speed it must reach there in blocks per tick. */
    record Limit(double at, double speed) {}

    private final Set<Integer> claimed = new HashSet<>();
    private int interval;
    private @Nullable RailPos waitingFor;

    /** Returns the junction the cart waits for because another cart holds it, or null when it waits for none. */
    @Nullable
    RailPos waitingFor() {
        return waitingFor;
    }

    /**
     * Returns how far the cart may ride this tick and which speed it reaches there.
     * A junction that cannot be slowed for yet still switches before the cart rides onto it.
     *
     * @param switches the shape the route wants of each junction, by its index on the path
     * @param speed    the speed the cart had on its last tick; zero for a cart that stands
     * @param reach    the distance up to which the junctions are read
     * @param before   how far before a junction set the wrong way it switches
     * @param crawl    the speed reached at the switch point, in blocks per tick
     */
    Limit limit(
            final RidePath path,
            final Map<Integer, RailShape> switches,
            final double along,
            final double speed,
            final double reach,
            final double before,
            final double crawl,
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
        final double committed = along + speed + motion.stopping(speed) + before;
        for (final int index : switches.keySet()) {
            if (index >= on && path.start(index) <= committed) {
                take(path, index, junctions);
            }
        }
        waitingFor = null;
        if (interval > 0) {
            interval--;
        }
        final var ordered = switches.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .toList();
        for (int position = 0; position < ordered.size(); position++) {
            final Map.Entry<Integer, RailShape> junction = ordered.get(position);
            final int index = junction.getKey();
            final double start = path.start(index);
            if (index < on || (index == on && speed > 0) || start > reach) {
                continue;
            }
            final RailPos rail = path.rails().get(index);
            if (!junctions.wrong(rail, junction.getValue())) {
                continue;
            }
            if (!take(path, index, junctions)) {
                waitingFor = rail;
                return stop(along, start);
            }
            final double switchAt = start - before;
            final double slow = crawlUpTo(path, ordered, position, switchAt, reach, crawl, motion, junctions);
            final boolean late = !motion.brakes(speed, switchAt - along, slow);
            final double next = path.next(along, speed, switchAt, motion, slow);
            final boolean reached = late || along >= switchAt - AT || along + next >= switchAt - AT;
            if (interval > 0) {
                // The stop waits for the interval, and the crawl holds as long as it is the slower of the two.
                final Limit hold = stop(along, start);
                return reached || path.next(along, speed, hold.at(), motion) < next ? hold : new Limit(switchAt, slow);
            }
            if (!reached) {
                return new Limit(switchAt, slow);
            }
            junctions.set(rail, junction.getValue());
            interval = SWITCH_INTERVAL;
        }
        return new Limit(Double.POSITIVE_INFINITY, 0);
    }

    /**
     * Returns the crawl speed for the junction at {@code position}, lowered for a wrong junction close behind it.
     * A cart at that speed can still brake at its acceleration for the stop before the next junction set the wrong way,
     * which the switch interval may hold unswitched.
     */
    private static double crawlUpTo(
            final RidePath path,
            final List<Map.Entry<Integer, RailShape>> ordered,
            final int position,
            final double switchAt,
            final double reach,
            final double crawl,
            final RidePath.Motion motion,
            final Junctions junctions) {
        for (int later = position + 1; later < ordered.size(); later++) {
            final Map.Entry<Integer, RailShape> junction = ordered.get(later);
            final double start = path.start(junction.getKey());
            if (start > reach) {
                break;
            }
            if (junctions.wrong(path.rails().get(junction.getKey()), junction.getValue())) {
                final double room = Math.max(0, start - STOP_BEFORE - switchAt);
                return Math.min(crawl, Math.sqrt(2 * motion.acceleration() * room));
            }
        }
        return crawl;
    }

    private static Limit stop(final double along, final double junction) {
        return new Limit(Math.max(along, junction - STOP_BEFORE), 0);
    }

    private boolean take(final RidePath path, final int index, final Junctions junctions) {
        if (claimed.contains(index) || junctions.claim(path.rails().get(index))) {
            claimed.add(index);
            return true;
        }
        return false;
    }
}
