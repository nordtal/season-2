package eu.nordtal.season.smp.wall;

import eu.nordtal.season.smp.config.CustomBorderSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads the {@code custom-border} group into the wall of each world where it is on. */
final class Walls {

    private Walls() {}

    /** The wall and look by world name. A world that is off is left out; the group refuses a world twice. */
    static Map<String, Drawn> read(final CustomBorderSpec spec) {
        final Map<String, Drawn> walls = new HashMap<>();
        for (final CustomBorderSpec.World world : spec.worlds()) {
            if (!world.enabled()) {
                continue;
            }
            final List<Area> areas = new ArrayList<>();
            for (final CustomBorderSpec.Polygon polygon : world.polygons()) {
                // Fewer than three corners enclose nothing; the group's check refuses them, this keeps a read safe.
                if (polygon.corners().size() >= 3) {
                    areas.add(new Area(polygon.corners().stream()
                            .map(corner -> new Area.Point(corner.x() + 0.5, corner.z() + 0.5))
                            .toList()));
                }
            }
            if (!areas.isEmpty()) {
                final Wall wall = new Wall(areas);
                if (wall.hasOutline()) {
                    walls.put(world.world(), new Drawn(wall, Look.FIELD));
                }
            }
        }
        return Map.copyOf(walls);
    }

    /** The wall of one world and how it is drawn. */
    record Drawn(Wall wall, Look look) {}
}
