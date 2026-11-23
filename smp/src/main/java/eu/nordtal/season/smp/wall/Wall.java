package eu.nordtal.season.smp.wall;

import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The wall of one world: the areas a player stays inside and the panels along their outline.
 * Where two areas touch, the edge between them has inside on both sides and gets no panel.
 */
final class Wall {

    /** How far to each side of an edge the inside is tested, in blocks. */
    private static final double SIDE = 0.25;

    /** How far inside the border line each panel is drawn, in blocks, so it never coincides with a block face. */
    static final double INSET = 0.03;

    private static final int CELL = 16;

    private final java.awt.geom.Area region = new java.awt.geom.Area();
    private final List<Edge> edges = new ArrayList<>();
    private final Map<Long, List<Panel>> cells = new HashMap<>();
    private int panelCount;

    Wall(final List<Area> areas) {
        for (final Area area : areas) {
            final Path2D.Double path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
            if (area.corners().isEmpty()) {
                continue;
            }
            final Area.Point first = area.corners().getFirst();
            path.moveTo(first.x(), first.z());
            area.corners().forEach(point -> path.lineTo(point.x(), point.z()));
            path.closePath();
            region.add(new java.awt.geom.Area(path));
        }
        final var outline = region.getPathIterator(null);
        final double[] coords = new double[6];
        Area.Point start = new Area.Point(0, 0);
        Area.Point previous = start;
        while (!outline.isDone()) {
            final int segment = outline.currentSegment(coords);
            final Area.Point point = segment == PathIterator.SEG_CLOSE ? start : new Area.Point(coords[0], coords[1]);
            if (segment == PathIterator.SEG_MOVETO) {
                start = point;
            } else if (!previous.equals(point)) {
                edges.add(new Edge(previous, point));
            }
            previous = point;
            outline.next();
        }
        // Keep the display lattice anchored to each configured edge's first corner.
        for (final Area area : areas) {
            final List<Area.Point> corners = area.corners();
            for (int i = 0; i < corners.size(); i++) {
                final Edge edge = new Edge(corners.get(i), corners.get((i + 1) % corners.size()));
                if (edge.length() > 0) {
                    place(edge);
                }
            }
        }
    }

    boolean inside(final double x, final double z) {
        return region.contains(x, z);
    }

    /** Fraction of the movement at the first exit from the union, including an exit followed by re-entry. */
    double exit(final double x, final double z, final double toX, final double toZ) {
        final double dx = toX - x;
        final double dz = toZ - z;
        final TreeSet<Double> cuts = new TreeSet<>();
        cuts.add(0.0);
        cuts.add(1.0);
        for (final Edge edge : edges) {
            final double det = dx * edge.dz() - dz * edge.dx();
            if (det == 0) {
                continue;
            }
            final double ax = edge.from().x() - x;
            final double az = edge.from().z() - z;
            final double t = (ax * edge.dz() - az * edge.dx()) / det;
            final double u = (ax * dz - az * dx) / det;
            if (t > 0 && t < 1 && u >= 0 && u <= 1) {
                cuts.add(t);
            }
        }
        double last = 0;
        for (final double t : cuts.tailSet(0.0, false)) {
            final double mid = (t + last) / 2;
            if (!inside(x + mid * dx, z + mid * dz)) {
                return last;
            }
            last = t;
        }
        return inside(toX, toZ) ? Double.POSITIVE_INFINITY : 1;
    }

    /** Inward normal of the closest outline edge within the clearance of the predicted position. */
    double @Nullable [] nearbyNormal(final double x, final double z, final double clearance) {
        double best = clearance;
        double[] normal = null;
        for (final Edge edge : edges) {
            final double[] point = edge.closest(x, z);
            final double distance = Math.hypot(point[0] - x, point[1] - z);
            if (distance <= best) {
                best = distance;
                // Area's outline is oriented with its interior to the right.
                final double[] left = edge.normal();
                normal = new double[] {-left[0], -left[1]};
            }
        }
        return normal;
    }

    double distance(final double x, final double z) {
        double best = Double.POSITIVE_INFINITY;
        for (final Edge edge : edges) {
            final double[] point = edge.closest(x, z);
            best = Math.min(best, Math.hypot(x - point[0], z - point[1]));
        }
        return best;
    }

    double @Nullable [] reflect(final double x, final double z, final double dx, final double dz) {
        if (!inside(x, z)) {
            return null;
        }
        final double hit = exit(x, z, x + dx, z + dz);
        if (!Double.isFinite(hit)) {
            return null;
        }
        final double hx = x + dx * hit;
        final double hz = z + dz * hit;
        final double[] normal = inward(hx, hz);
        final double dot = dx * normal[0] + dz * normal[1];
        final double rx = dx - 2 * dot * normal[0];
        final double rz = dz - 2 * dot * normal[1];
        // At a corner the reflection in one edge can still leave through the other.
        if (Double.isFinite(exit(x, z, x + rx, z + rz))) {
            return new double[] {-dx, -dz};
        }
        return new double[] {rx, rz};
    }

    Area.Point returnPoint(final double x, final double z) {
        Area.Point nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (final Edge edge : edges) {
            final double[] point = edge.closest(x, z);
            final double distance = Math.hypot(point[0] - x, point[1] - z);
            if (distance >= best) {
                continue;
            }
            final double[] normal = edge.normal();
            // Move toward the edge midpoint first so a projection onto a corner also moves inside.
            final double mx = (edge.from().x() + edge.to().x()) / 2;
            final double mz = (edge.from().z() + edge.to().z()) / 2;
            final double along = Math.min(1, 0.025 / Math.max(0.025, Math.hypot(mx - point[0], mz - point[1])));
            final double px = point[0] + along * (mx - point[0]);
            final double pz = point[1] + along * (mz - point[1]);
            for (double inset = 0.025; inset > 1e-12; inset /= 2) {
                if (inside(px - normal[0] * inset, pz - normal[1] * inset)) {
                    nearest = new Area.Point(px - normal[0] * inset, pz - normal[1] * inset);
                    best = distance;
                    break;
                }
            }
        }
        return Objects.requireNonNull(nearest);
    }

    int panelCount() {
        return panelCount;
    }

    /** Every panel whose centre lies within {@code radius} of the point. */
    List<Panel> near(final double x, final double z, final double radius) {
        final List<Panel> found = new ArrayList<>();
        final int fromX = Math.floorDiv((int) Math.floor(x - radius), CELL);
        final int toX = Math.floorDiv((int) Math.floor(x + radius), CELL);
        final int fromZ = Math.floorDiv((int) Math.floor(z - radius), CELL);
        final int toZ = Math.floorDiv((int) Math.floor(z + radius), CELL);
        for (int cellX = fromX; cellX <= toX; cellX++) {
            for (int cellZ = fromZ; cellZ <= toZ; cellZ++) {
                for (final Panel panel : cells.getOrDefault(key(cellX, cellZ), List.of())) {
                    if (panel.distance(x, z) <= radius) {
                        found.add(panel);
                    }
                }
            }
        }
        return found;
    }

    /**
     * The direction back inside from a point outside, as a unit vector {x, z}.
     *
     * Towards the nearest point of the outline, or along that edge's inward normal when the point lies on it.
     */
    double[] inward(final double x, final double z) {
        Edge nearest = edges.getFirst();
        double best = Double.MAX_VALUE;
        double[] point = {0, 0};
        for (final Edge edge : edges) {
            final double[] candidate = edge.closest(x, z);
            final double distance = Math.hypot(candidate[0] - x, candidate[1] - z);
            if (distance < best) {
                best = distance;
                nearest = edge;
                point = candidate;
            }
        }
        if (best > 0.05) {
            return new double[] {(point[0] - x) / best, (point[1] - z) / best};
        }
        final double[] normal = nearest.normal();
        final boolean towardsInside = inside(point[0] + normal[0] * SIDE, point[1] + normal[1] * SIDE);
        return towardsInside ? normal : new double[] {-normal[0], -normal[1]};
    }

    boolean hasOutline() {
        return !edges.isEmpty();
    }

    /**
     * Panels one block wide on whole-block slots counted from the edge's first corner, so the lattice stands still.
     *
     * An edge of no whole length ends with a panel that overlaps the one before it.
     */
    private void place(final Edge edge) {
        final double length = edge.length();
        final int count = (int) Math.ceil(length);
        final double[] normal = edge.normal();
        final float yaw = (float) Math.toDegrees(Math.atan2(edge.dz(), edge.dx()));
        for (int i = 0; i < count; i++) {
            final double along = Math.max(0.5, Math.min(i + 0.5, length - 0.5)) / length;
            final double x = edge.from().x() + edge.dx() * along;
            final double z = edge.from().z() + edge.dz() * along;
            final boolean left = inside(x + normal[0] * SIDE, z + normal[1] * SIDE);
            final boolean right = inside(x - normal[0] * SIDE, z - normal[1] * SIDE);
            if (left != right) {
                final double sign = left ? 1 : -1;
                final double px = x + sign * normal[0] * INSET;
                final double pz = z + sign * normal[1] * INSET;
                // Orient yaw so (-sin(yaw), cos(yaw)) always points inward.
                final float orientedYaw = left ? yaw : (yaw + 180f) % 360f;
                final Panel panel = new Panel(px, pz, orientedYaw);
                cells.computeIfAbsent(
                                key(
                                        Math.floorDiv((int) Math.floor(px), CELL),
                                        Math.floorDiv((int) Math.floor(pz), CELL)),
                                ignored -> new ArrayList<>())
                        .add(panel);
                panelCount++;
            }
        }
    }

    private static long key(final int cellX, final int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xffffffffL);
    }

    private record Edge(Area.Point from, Area.Point to) {

        double dx() {
            return to.x() - from.x();
        }

        double dz() {
            return to.z() - from.z();
        }

        double length() {
            return Math.hypot(dx(), dz());
        }

        double[] normal() {
            final double length = length();
            return new double[] {-dz() / length, dx() / length};
        }

        double[] closest(final double x, final double z) {
            final double length = length();
            final double t = Math.clamp(((x - from.x()) * dx() + (z - from.z()) * dz()) / (length * length), 0, 1);
            return new double[] {from.x() + dx() * t, from.z() + dz() * t};
        }
    }
}
