package eu.nordtal.season.smp.area;

import eu.nordtal.season.database.inbox.AreaShape;
import eu.nordtal.season.database.inbox.AreaShape.Corner;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The geometry of an area's polygons: what makes one unsaveable, and where the editor's clicks land on it.
 * Every area of a world, flat or with heights, shares it.
 */
public final class Polygons {

    /** The lowest block on either axis of the overworld's map. */
    public static final int MAP_MIN = 0;

    /** The highest block on either axis of the overworld's map, inside the vanilla border of 10240 blocks. */
    public static final int MAP_MAX = 10239;

    private Polygons() {}

    /**
     * Why a shape cannot be saved, by the field it names.
     *
     * @param field the key of the shape's setting that is wrong, such as {@code corners}
     */
    public record Problem(String field, String sentence) {}

    /**
     * Returns why {@code shape} cannot be saved, or null when it can.
     *
     * @param heights whether the area has a bottom and a top, which a flat border region has not
     * @param overworld whether the shape lies in the overworld, whose map ends at {@link #MAP_MIN} and {@link #MAP_MAX}
     */
    public static @Nullable Problem problem(final AreaShape shape, final boolean heights, final boolean overworld) {
        final List<Corner> corners = shape.corners();
        if (corners.size() < 3) {
            return new Problem(
                    "corners", "the area \"" + shape.name() + "\" needs at least 3 corners, has " + corners.size());
        }
        if (overworld) {
            for (final Corner corner : corners) {
                if (!onMap(corner.x()) || !onMap(corner.z())) {
                    return new Problem(
                            "corners",
                            "the area \"" + shape.name() + "\" has a corner at " + corner.x() + ", " + corner.z()
                                    + " outside the map from " + MAP_MIN + " to " + MAP_MAX);
                }
            }
        }
        if (selfIntersects(corners)) {
            return new Problem("corners", "the edges of the area \"" + shape.name() + "\" cross or touch each other");
        }
        if (heights && shape.bottom() > shape.top()) {
            return new Problem(
                    "bottom",
                    "the area \"" + shape.name() + "\" has its bottom at " + shape.bottom() + ", above its top at "
                            + shape.top());
        }
        return null;
    }

    private static boolean onMap(final int value) {
        return value >= MAP_MIN && value <= MAP_MAX;
    }

    /**
     * Whether any two edges of the closed polygon cross or touch, other than two neighbours at the corner they share.
     * Two corners at the same place count, since the edge between them has no length.
     */
    static boolean selfIntersects(final List<Corner> corners) {
        final int n = corners.size();
        for (int i = 0; i < n; i++) {
            final Corner a = corners.get(i);
            final Corner b = corners.get((i + 1) % n);
            if (a.equals(b)) {
                return true;
            }
            for (int j = i + 1; j < n; j++) {
                final Corner c = corners.get(j);
                final Corner d = corners.get((j + 1) % n);
                final boolean next = j == i + 1;
                final boolean previous = i == 0 && j == n - 1;
                if (next || previous) {
                    // Neighbours share one corner; they overlap only when one folds back along the other.
                    final Corner shared = next ? b : a;
                    final Corner own = next ? a : b;
                    final Corner other = next ? d : c;
                    if (cross(shared, own, other) == 0 && dot(shared, own, other) > 0) {
                        return true;
                    }
                } else if (touch(a, b, c, d)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long cross(final Corner origin, final Corner a, final Corner b) {
        return (long) (a.x() - origin.x()) * (b.z() - origin.z()) - (long) (a.z() - origin.z()) * (b.x() - origin.x());
    }

    private static long dot(final Corner origin, final Corner a, final Corner b) {
        return (long) (a.x() - origin.x()) * (b.x() - origin.x()) + (long) (a.z() - origin.z()) * (b.z() - origin.z());
    }

    /** Whether segment {@code a}{@code b} and segment {@code c}{@code d} share any point. */
    private static boolean touch(final Corner a, final Corner b, final Corner c, final Corner d) {
        final long d1 = Long.signum(cross(c, d, a));
        final long d2 = Long.signum(cross(c, d, b));
        final long d3 = Long.signum(cross(a, b, c));
        final long d4 = Long.signum(cross(a, b, d));
        if (d1 * d2 < 0 && d3 * d4 < 0) {
            return true;
        }
        return (d1 == 0 && within(c, d, a))
                || (d2 == 0 && within(c, d, b))
                || (d3 == 0 && within(a, b, c))
                || (d4 == 0 && within(a, b, d));
    }

    /** Whether {@code p}, which lies on the line through {@code a} and {@code b}, lies between them. */
    private static boolean within(final Corner a, final Corner b, final Corner p) {
        return Math.min(a.x(), b.x()) <= p.x()
                && p.x() <= Math.max(a.x(), b.x())
                && Math.min(a.z(), b.z()) <= p.z()
                && p.z() <= Math.max(a.z(), b.z());
    }

    /**
     * Returns the index of the corner that starts the edge nearest to {@code x}, {@code z} within {@code reach}.
     * The polygon is closed. It returns -1 when no edge is that close.
     */
    public static int nearestEdge(final List<Corner> corners, final double x, final double z, final double reach) {
        int nearest = -1;
        double best = reach;
        for (int i = 0; i < corners.size(); i++) {
            final double[] at = closest(corners.get(i), corners.get((i + 1) % corners.size()), x, z);
            final double distance = Math.hypot(at[0] - x, at[1] - z);
            if (distance <= best) {
                best = distance;
                nearest = i;
            }
        }
        return nearest;
    }

    /** Returns the point of the segment from {@code a} to {@code b} nearest to {@code x}, {@code z}. */
    static double[] closest(final Corner a, final Corner b, final double x, final double z) {
        final double dx = b.x() - a.x();
        final double dz = b.z() - a.z();
        final double length = dx * dx + dz * dz;
        final double t = length == 0 ? 0 : Math.clamp(((x - a.x()) * dx + (z - a.z()) * dz) / length, 0, 1);
        return new double[] {a.x() + t * dx, a.z() + t * dz};
    }

    /**
     * Returns the height on the vertical line through {@code x}, {@code z} nearest to the look along {@code direction}.
     * The look starts at {@code eye}. A height handle follows where its admin looks this way.
     */
    public static double heightAt(final double[] eye, final double[] direction, final double x, final double z) {
        // The look p(t) = eye + t * direction and the line q(s) = (x, s, z); the nearest s solves both derivatives.
        final double horizontal = direction[0] * direction[0] + direction[2] * direction[2];
        if (horizontal < 1e-9) {
            return eye[1];
        }
        final double t = Math.max(0, ((x - eye[0]) * direction[0] + (z - eye[2]) * direction[2]) / horizontal);
        return eye[1] + t * direction[1];
    }

    /** Returns the square of {@code half} blocks to each side of {@code x}, {@code z}, its corners in order. */
    public static List<Corner> square(final int x, final int z, final int half) {
        return List.of(
                new Corner(x - half, z - half),
                new Corner(x + half, z - half),
                new Corner(x + half, z + half),
                new Corner(x - half, z + half));
    }
}
