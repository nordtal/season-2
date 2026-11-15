package eu.nordtal.season.smp.wall;

import java.util.List;

/** One area a player stays inside: a polygon of corners in order, whose last corner joins the first. */
record Area(List<Point> corners) {

    Area {
        corners = List.copyOf(corners);
    }

    /** Whether the point lies inside, by the even-odd rule. A point on an edge may count either way. */
    boolean contains(final double x, final double z) {
        boolean inside = false;
        final int size = corners.size();
        for (int i = 0, j = size - 1; i < size; j = i++) {
            final Point a = corners.get(i);
            final Point b = corners.get(j);
            if ((a.z() > z) != (b.z() > z) && x < (b.x() - a.x()) * (z - a.z()) / (b.z() - a.z()) + a.x()) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** A point in the plane of the map, in blocks. */
    record Point(double x, double z) {}
}
