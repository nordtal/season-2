package eu.nordtal.season.smp.wall;

import eu.nordtal.season.smp.config.CustomBorderSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads the {@code custom-border} group into the wall of each world where it is on. */
final class Walls {

    private Walls() {}

    /** The wall and look by world name. A world that is off, or a second entry of a world, is left out. */
    static Map<String, Drawn> read(final CustomBorderSpec spec) {
        final Map<String, Drawn> walls = new HashMap<>();
        for (final CustomBorderSpec.World world : spec.worlds()) {
            if (!world.on() || walls.containsKey(world.world())) {
                continue;
            }
            final List<Area> areas = new ArrayList<>();
            for (final CustomBorderSpec.Polygon polygon : world.polygons()) {
                // Fewer than three corners enclose nothing.
                if (polygon.corners().size() >= 3) {
                    areas.add(new Area(polygon.corners().stream()
                            .map(corner -> new Area.Point(corner.x(), corner.z()))
                            .toList()));
                }
            }
            if (!areas.isEmpty()) {
                walls.put(world.world(), new Drawn(new Wall(areas), Look.named(world.look())));
            }
        }
        return Map.copyOf(walls);
    }

    /** The wall of one world and how it is drawn. */
    record Drawn(Wall wall, Look look) {}
}
