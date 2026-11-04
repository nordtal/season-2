package eu.nordtal.season.smp.rail;

import java.util.Arrays;
import java.util.List;

/**
 * The line a driven cart follows over the rails of a route, and the speed it may have along it.
 * Each rail spans the stretch from the edge it is entered at to the edge it is left at, so a curve is cut
 * diagonally as vanilla does. Distances are in blocks and speeds in blocks per tick.
 */
final class RidePath {

    /** A point of the line, where a cart stands. */
    record Point(double x, double y, double z) {}

    /**
     * How a driven cart speeds up and slows down, in blocks per tick.
     *
     * @param acceleration the speed gained each tick, and lost each tick while braking
     */
    record Motion(double acceleration, double top, double curve) {

        /** Returns the motion of the settings, given in blocks per second. */
        static Motion of(final double acceleration, final double top, final double curve) {
            return new Motion(
                    acceleration / (TICKS_PER_SECOND * TICKS_PER_SECOND),
                    top / TICKS_PER_SECOND,
                    Math.min(curve, top) / TICKS_PER_SECOND);
        }

        /** Returns the distance a cart at {@code speed} needs to stop. */
        double stopping(final double speed) {
            return speed * speed / (2 * acceleration);
        }
    }

    static final int TICKS_PER_SECOND = 20;

    private final List<RailPos> rails;
    private final Point[] points;
    private final double[] along;
    private final double[] limits;

    private RidePath(final List<RailPos> rails, final Point[] points, final double[] limits) {
        this.rails = List.copyOf(rails);
        this.points = points;
        this.limits = limits;
        this.along = new double[points.length];
        for (int i = 1; i < points.length; i++) {
            along[i] = along[i - 1] + distance(points[i - 1], points[i]);
        }
    }

    /**
     * Lays the line over {@code rails}, two or more in riding order, from {@code start} to the last rail's middle.
     * A curve limits the speed over its own rail.
     */
    static RidePath of(final List<RailPos> rails, final Point start, final Motion motion) {
        final Point[] points = new Point[rails.size() + 1];
        points[0] = start;
        for (int i = 1; i < rails.size(); i++) {
            points[i] = edge(rails.get(i - 1), rails.get(i));
        }
        final RailPos last = rails.getLast();
        points[rails.size()] = new Point(last.x() + 0.5, last.y(), last.z() + 0.5);
        final double[] limits = new double[rails.size()];
        Arrays.fill(limits, motion.top());
        for (int i = 0; i < rails.size(); i++) {
            if (i > 0 && i < rails.size() - 1 && turns(rails.get(i - 1), rails.get(i), rails.get(i + 1))) {
                limits[i] = Math.min(limits[i], motion.curve());
            }
        }
        return new RidePath(rails, points, limits);
    }

    /** Returns the point where the rails {@code a} and {@code b}, one step apart, meet. */
    private static Point edge(final RailPos a, final RailPos b) {
        return new Point((a.x() + b.x()) / 2.0 + 0.5, Math.max(a.y(), b.y()), (a.z() + b.z()) / 2.0 + 0.5);
    }

    private static boolean turns(final RailPos before, final RailPos at, final RailPos after) {
        return Heading.between(before, at) != Heading.between(at, after);
    }

    private static double distance(final Point a, final Point b) {
        return Math.sqrt(Math.pow(b.x() - a.x(), 2) + Math.pow(b.y() - a.y(), 2) + Math.pow(b.z() - a.z(), 2));
    }

    List<RailPos> rails() {
        return rails;
    }

    double length() {
        return along[along.length - 1];
    }

    /** Returns the distance at which the cart rides onto the rail with {@code index}. */
    double start(final int index) {
        return along[index];
    }

    /** Returns the index of the rail the cart is on at {@code distance}. */
    int railAt(final double distance) {
        final int found = Arrays.binarySearch(along, distance);
        final int point = found >= 0 ? found : -found - 2;
        return Math.clamp(point, 0, rails.size() - 1);
    }

    /** Returns where the cart stands at {@code distance}. */
    Point at(final double distance) {
        final int rail = railAt(distance);
        final Point from = points[rail];
        final Point to = points[rail + 1];
        final double span = along[rail + 1] - along[rail];
        final double part = span <= 0 ? 0 : Math.clamp((distance - along[rail]) / span, 0, 1);
        return new Point(
                from.x() + (to.x() - from.x()) * part,
                from.y() + (to.y() - from.y()) * part,
                from.z() + (to.z() - from.z()) * part);
    }

    /** Returns the horizontal direction of travel at {@code distance}, as an x and z of length one. */
    double[] direction(final double distance) {
        final int rail = railAt(distance);
        final double dx = points[rail + 1].x() - points[rail].x();
        final double dz = points[rail + 1].z() - points[rail].z();
        final double length = Math.hypot(dx, dz);
        if (length == 0) {
            final Heading heading = rail + 1 < rails.size()
                    ? Heading.between(rails.get(rail), rails.get(rail + 1))
                    : Heading.between(rails.get(rail - 1), rails.get(rail));
            return new double[] {heading.dx(), heading.dz()};
        }
        return new double[] {dx / length, dz / length};
    }

    /**
     * Returns the speed for the next tick of a cart at {@code distance} that rides at {@code speed} now.
     * It speeds up towards the top speed, brakes in time for every slower rail ahead, and comes to a stop at
     * {@code end}, where it must stand still.
     */
    double next(final double distance, final double speed, final double end, final Motion motion) {
        final double left = Math.max(0, end - distance);
        if (left <= motion.acceleration()) {
            return Math.min(left, speed + motion.acceleration());
        }
        double allowed = Math.min(motion.top(), reaching(0, left, motion.acceleration()));
        final double ahead = motion.stopping(motion.top()) + 1;
        for (int rail = railAt(distance); rail < limits.length && along[rail] - distance <= ahead; rail++) {
            final double before = along[rail] - distance;
            allowed = Math.min(
                    allowed, before <= 0 ? limits[rail] : reaching(limits[rail], before, motion.acceleration()));
        }
        return Math.max(0, Math.min(speed + motion.acceleration(), allowed));
    }

    /**
     * Returns the highest speed at which a cart may ride on and still slow to {@code target} within {@code distance}.
     * It rides one tick at that speed and brakes from then on; a speed at or below the target never needs braking.
     */
    private static double reaching(final double target, final double distance, final double braking) {
        return Math.max(target, -braking + Math.sqrt(braking * braking + target * target + 2 * braking * distance));
    }
}
