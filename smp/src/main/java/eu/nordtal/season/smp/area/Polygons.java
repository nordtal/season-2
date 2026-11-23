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

    /**
     * Whether the block at {@code x}, {@code z} lies inside or on the boundary of {@code corners}.
     * Polygon corners name blocks and edges run through block centres, so a block centre on an edge counts as inside.
     */
    public static boolean containsBlock(final List<Corner> corners, final int x, final int z) {
        final int n = corners.size();
        if (n < 3) {
            return false;
        }
        final Corner point = new Corner(x, z);
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            final Corner a = corners.get(j);
            final Corner b = corners.get(i);
            if (cross(a, b, point) == 0 && within(a, b, point)) {
                return true;
            }
            if ((a.z() > z) != (b.z() > z)) {
                final long lhs = (long) (x - a.x()) * (b.z() - a.z());
                final long rhs = (long) (b.x() - a.x()) * (z - a.z());
                if (b.z() > a.z() ? lhs < rhs : lhs > rhs) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    /** Whether the continuous point {@code x}, {@code z} lies inside or on the boundary of {@code corners}. */
    public static boolean containsPoint(final List<Corner> corners, final double x, final double z) {
        final int n = corners.size();
        if (n < 3) {
            return false;
        }
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            final double ax = corners.get(j).x() + 0.5;
            final double az = corners.get(j).z() + 0.5;
            final double bx = corners.get(i).x() + 0.5;
            final double bz = corners.get(i).z() + 0.5;
            if (pointOnSegment(ax, az, bx, bz, x, z)) {
                return true;
            }
            if ((az > z) != (bz > z) && x < (bx - ax) * (z - az) / (bz - az) + ax) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static boolean pointOnSegment(
            final double ax, final double az, final double bx, final double bz, final double x, final double z) {
        final double dx = bx - ax;
        final double dz = bz - az;
        final double lengthSquared = dx * dx + dz * dz;
        final double t = lengthSquared == 0 ? 0 : Math.clamp(((x - ax) * dx + (z - az) * dz) / lengthSquared, 0, 1);
        final double px = ax + t * dx;
        final double pz = az + t * dz;
        return Math.hypot(px - x, pz - z) <= 1e-9;
    }

    /** Returns the average of the polygon's block centres as {@code {x, z}}. */
    public static double[] centre(final List<Corner> corners) {
        double x = 0;
        double z = 0;
        for (final Corner corner : corners) {
            x += corner.x() + 0.5;
            z += corner.z() + 0.5;
        }
        final int n = Math.max(1, corners.size());
        return new double[] {x / n, z / n};
    }

    /**
     * Returns a unit vector {@code {x, z}} pushing a point at {@code x}, {@code z} out of {@code corners}.
     * Points toward the nearest boundary edge, or along that edge's outward normal when standing on it.
     */
    public static double[] outward(final List<Corner> corners, final double x, final double z) {
        final int n = corners.size();
        if (n < 3) {
            return new double[] {1, 0};
        }
        double best = Double.POSITIVE_INFINITY;
        double bestPx = x;
        double bestPz = z;
        double bestDx = 1;
        double bestDz = 0;
        for (int i = 0; i < n; i++) {
            final double ax = corners.get(i).x() + 0.5;
            final double az = corners.get(i).z() + 0.5;
            final double bx = corners.get((i + 1) % n).x() + 0.5;
            final double bz = corners.get((i + 1) % n).z() + 0.5;
            final double dx = bx - ax;
            final double dz = bz - az;
            final double lengthSquared = dx * dx + dz * dz;
            final double t = lengthSquared == 0 ? 0 : Math.clamp(((x - ax) * dx + (z - az) * dz) / lengthSquared, 0, 1);
            final double px = ax + t * dx;
            final double pz = az + t * dz;
            final double distance = Math.hypot(px - x, pz - z);
            if (distance < best) {
                best = distance;
                bestPx = px;
                bestPz = pz;
                bestDx = dx;
                bestDz = dz;
            }
        }
        if (best > 0.05) {
            final boolean inside = containsPoint(corners, x, z);
            final double sign = inside ? 1 : -1;
            return new double[] {sign * (bestPx - x) / best, sign * (bestPz - z) / best};
        }
        final double length = Math.hypot(bestDx, bestDz);
        if (length < 1e-9) {
            return new double[] {1, 0};
        }
        final double nx = -bestDz / length;
        final double nz = bestDx / length;
        return containsPoint(corners, bestPx + nx * 0.25, bestPz + nz * 0.25)
                ? new double[] {-nx, -nz}
                : new double[] {nx, nz};
    }

    /** Returns a point {@code margin} blocks outside the nearest edge of {@code corners} from {@code x}, {@code z}. */
    public static double[] outsidePoint(
            final List<Corner> corners, final double x, final double z, final double margin) {
        final int n = corners.size();
        if (n < 3) {
            return new double[] {x, z};
        }
        double best = Double.POSITIVE_INFINITY;
        double bestPx = x;
        double bestPz = z;
        double bestAx = 0;
        double bestAz = 0;
        double bestBx = 0;
        double bestBz = 0;
        for (int i = 0; i < n; i++) {
            final double ax = corners.get(i).x() + 0.5;
            final double az = corners.get(i).z() + 0.5;
            final double bx = corners.get((i + 1) % n).x() + 0.5;
            final double bz = corners.get((i + 1) % n).z() + 0.5;
            final double dx = bx - ax;
            final double dz = bz - az;
            final double lengthSquared = dx * dx + dz * dz;
            final double t = lengthSquared == 0 ? 0 : Math.clamp(((x - ax) * dx + (z - az) * dz) / lengthSquared, 0, 1);
            final double px = ax + t * dx;
            final double pz = az + t * dz;
            final double distance = Math.hypot(px - x, pz - z);
            if (distance < best) {
                best = distance;
                bestPx = px;
                bestPz = pz;
                bestAx = ax;
                bestAz = az;
                bestBx = bx;
                bestBz = bz;
            }
        }
        final double dx = bestBx - bestAx;
        final double dz = bestBz - bestAz;
        final double length = Math.hypot(dx, dz);
        if (length < 1e-9) {
            return new double[] {x, z};
        }
        final double nx = -dz / length;
        final double nz = dx / length;
        final double mx = (bestAx + bestBx) / 2;
        final double mz = (bestAz + bestBz) / 2;
        final boolean positiveIsInside = containsPoint(corners, mx + nx * 0.25, mz + nz * 0.25);
        final double ox = positiveIsInside ? -nx : nx;
        final double oz = positiveIsInside ? -nz : nz;
        return new double[] {bestPx + ox * margin, bestPz + oz * margin};
    }
}
