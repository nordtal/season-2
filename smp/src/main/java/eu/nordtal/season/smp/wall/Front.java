package eu.nordtal.season.smp.wall;

import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The playable area of a world whose regions open with milestones.
 * A newly opened region becomes playable where its distance to the area before it is no more than the front has
 * travelled since the opening.
 */
final class Front {

    /** The sides of the polygon that stands for the circle the front draws around each corner it spreads from. */
    static final int ROUND = 64;

    /** A front closer than this to the area it spreads from has not reached anything yet, in blocks. */
    static final double FIRST = 0.5;

    private Front() {}

    /**
     * One opening of regions.
     *
     * @param start when the front set out, in milliseconds
     */
    record Stage(Set<String> regions, long start) {

        Stage {
            regions = Set.copyOf(regions);
        }
    }

    /**
     * The playable area at one moment.
     *
     * @param region what a player may stand on
     * @param lattice the polygons whose edges carry the wall's panels where they lie on the region's outline
     * @param spreading the stages whose front has not reached the whole of their regions yet, in their order
     */
    record Shape(java.awt.geom.Area region, List<Area> lattice, List<Stage> spreading) {

        Shape {
            lattice = List.copyOf(lattice);
            spreading = List.copyOf(spreading);
        }
    }

    /**
     * Returns the playable area of {@code regions} at {@code now}.
     * Each stage spreads from everything playable before it; a stage onto an empty area opens at once.
     *
     * @param regions the polygons of the world by their name, where one name may hold several
     * @param open the names that are fully playable
     * @param speed how far a front travels, in blocks per second
     */
    static Shape at(
            final Map<String, List<Area>> regions,
            final Set<String> open,
            final List<Stage> stages,
            final long now,
            final double speed) {
        final java.awt.geom.Area playable = new java.awt.geom.Area();
        final List<Area> lattice = new ArrayList<>();
        for (final String name : open) {
            for (final Area area : regions.getOrDefault(name, List.of())) {
                playable.add(region(area));
                lattice.add(area);
            }
        }
        final List<Stage> spreading = new ArrayList<>();
        for (final Stage stage : stages) {
            final java.awt.geom.Area opened = new java.awt.geom.Area();
            for (final String name : stage.regions()) {
                for (final Area area : regions.getOrDefault(name, List.of())) {
                    opened.add(region(area));
                    lattice.add(area);
                }
            }
            final java.awt.geom.Area pending = new java.awt.geom.Area(opened);
            pending.subtract(playable);
            if (playable.isEmpty() || pending.isEmpty()) {
                playable.add(opened);
                continue;
            }
            final double travelled = speed * Math.max(0, now - stage.start()) / 1000.0;
            if (travelled < FIRST) {
                spreading.add(stage);
                continue;
            }
            final java.awt.geom.Area grown = grown(playable, travelled);
            pending.subtract(grown);
            final java.awt.geom.Area reach = new java.awt.geom.Area(grown);
            reach.intersect(opened);
            playable.add(reach);
            if (pending.isEmpty()) {
                continue;
            }
            spreading.add(stage);
            // The grown outline, not the reached one, so no panel doubles one on a region's own edge.
            lattice.addAll(outlines(grown));
        }
        return new Shape(playable, lattice, spreading);
    }

    /**
     * Every point no farther than {@code distance} from {@code area}, with corners rounded as {@link #ROUND}-gons.
     */
    static java.awt.geom.Area grown(final java.awt.geom.Area area, final double distance) {
        final java.awt.geom.Area grown = new java.awt.geom.Area(area);
        for (final Area outline : outlines(area)) {
            final List<Area.Point> corners = outline.corners();
            for (int i = 0; i < corners.size(); i++) {
                final Area.Point from = corners.get(i);
                final Area.Point to = corners.get((i + 1) % corners.size());
                final double length = Math.hypot(to.x() - from.x(), to.z() - from.z());
                if (length > 0) {
                    final double nx = -(to.z() - from.z()) / length * distance;
                    final double nz = (to.x() - from.x()) / length * distance;
                    grown.add(region(new Area(List.of(
                            new Area.Point(from.x() + nx, from.z() + nz),
                            new Area.Point(to.x() + nx, to.z() + nz),
                            new Area.Point(to.x() - nx, to.z() - nz),
                            new Area.Point(from.x() - nx, from.z() - nz)))));
                }
                grown.add(region(circle(from, distance)));
            }
        }
        return grown;
    }

    /** Each closed outline of {@code area} as a polygon. */
    static List<Area> outlines(final java.awt.geom.Area area) {
        final List<Area> found = new ArrayList<>();
        final PathIterator path = area.getPathIterator(null);
        final double[] coords = new double[6];
        List<Area.Point> corners = new ArrayList<>();
        while (!path.isDone()) {
            final int segment = path.currentSegment(coords);
            if (segment == PathIterator.SEG_MOVETO) {
                corners = new ArrayList<>();
                corners.add(new Area.Point(coords[0], coords[1]));
            } else if (segment == PathIterator.SEG_LINETO) {
                final Area.Point point = new Area.Point(coords[0], coords[1]);
                if (!point.equals(corners.getLast())) {
                    corners.add(point);
                }
            } else if (segment == PathIterator.SEG_CLOSE) {
                if (corners.size() > 1 && corners.getFirst().equals(corners.getLast())) {
                    corners.removeLast();
                }
                if (corners.size() >= 3) {
                    found.add(new Area(corners));
                }
            }
            path.next();
        }
        return found;
    }

    static java.awt.geom.Area region(final Area area) {
        final Path2D.Double path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        final Area.Point first = area.corners().getFirst();
        path.moveTo(first.x(), first.z());
        area.corners().forEach(point -> path.lineTo(point.x(), point.z()));
        path.closePath();
        return new java.awt.geom.Area(path);
    }

    private static Area circle(final Area.Point centre, final double radius) {
        final List<Area.Point> corners = new ArrayList<>(ROUND);
        for (int i = 0; i < ROUND; i++) {
            final double angle = 2 * Math.PI * i / ROUND;
            corners.add(new Area.Point(centre.x() + radius * Math.cos(angle), centre.z() + radius * Math.sin(angle)));
        }
        return new Area(corners);
    }
}
