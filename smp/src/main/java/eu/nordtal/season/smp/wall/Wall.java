package eu.nordtal.season.smp.wall;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The wall of one world: the areas a player stays inside and the panels along their outline.
 * Where two areas touch, the edge between them has inside on both sides and gets no panel.
 */
final class Wall {

    /** How far to each side of an edge the inside is tested, in blocks. */
    private static final double SIDE = 0.25;

    private static final int CELL = 16;

    private final List<Area> areas;
    private final List<Edge> edges = new ArrayList<>();
    private final Map<Long, List<Panel>> cells = new HashMap<>();
    private int panelCount;

    Wall(final List<Area> areas) {
        this.areas = List.copyOf(areas);
        for (final Area area : this.areas) {
            final List<Area.Point> corners = area.corners();
            for (int i = 0; i < corners.size(); i++) {
                final Edge edge = new Edge(corners.get(i), corners.get((i + 1) % corners.size()));
                if (edge.length() > 0) {
                    edges.add(edge);
                    place(edge);
                }
            }
        }
    }

    boolean inside(final double x, final double z) {
        for (final Area area : areas) {
            if (area.contains(x, z)) {
                return true;
            }
        }
        return false;
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
                final Panel panel = new Panel(x, z, yaw);
                cells.computeIfAbsent(
                                key(Math.floorDiv((int) Math.floor(x), CELL), Math.floorDiv((int) Math.floor(z), CELL)),
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
