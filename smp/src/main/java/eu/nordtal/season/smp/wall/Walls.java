package eu.nordtal.season.smp.wall;

import eu.nordtal.season.smp.config.CustomBorderSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads the {@code custom-border} group into the wall of each world where it is on. */
final class Walls {

    private Walls() {}

    /**
     * Returns the wall and look by world name; off or empty worlds are omitted.
     * Only milestone-opened regions are playable in {@code overworld}; every other world holds all its areas.
     *
     * @param open the overworld's regions that are fully playable, by name
     * @param stages the overworld's openings whose front still spreads
     * @param speed how far a front travels, in blocks per second
     */
    static Read read(
            final CustomBorderSpec spec,
            final String overworld,
            final Set<String> open,
            final List<Front.Stage> stages,
            final long now,
            final double speed) {
        final Map<String, Drawn> walls = new HashMap<>();
        List<Front.Stage> spreading = List.of();
        boolean overworldEvaluated = false;
        for (final CustomBorderSpec.World world : spec.worlds()) {
            if (!world.enabled()) {
                continue;
            }
            final Map<String, List<Area>> regions = regionsOf(world);
            final Wall wall;
            if (world.world().equals(overworld)) {
                overworldEvaluated = true;
                final Front.Shape shape = Front.at(regions, open, stages, now, speed);
                spreading = shape.spreading();
                wall = new Wall(shape.region(), shape.lattice());
            } else {
                wall = new Wall(regions.values().stream().flatMap(List::stream).toList());
            }
            if (wall.hasOutline()) {
                walls.put(world.world(), new Drawn(wall, Look.FIELD));
            }
        }
        return new Read(Map.copyOf(walls), spreading, overworldEvaluated);
    }

    /** Rebuilds only the overworld wall, keeping every other world's cached wall untouched. */
    static Read readOverworld(
            final CustomBorderSpec spec,
            final String overworld,
            final Map<String, Drawn> existing,
            final Set<String> open,
            final List<Front.Stage> stages,
            final long now,
            final double speed) {
        final Map<String, Drawn> walls = new HashMap<>(existing);
        walls.remove(overworld);
        List<Front.Stage> spreading = List.of();
        boolean overworldEvaluated = false;
        for (final CustomBorderSpec.World world : spec.worlds()) {
            if (!world.enabled() || !world.world().equals(overworld)) {
                continue;
            }
            overworldEvaluated = true;
            final Front.Shape shape = Front.at(regionsOf(world), open, stages, now, speed);
            spreading = shape.spreading();
            final Wall wall = new Wall(shape.region(), shape.lattice());
            if (wall.hasOutline()) {
                walls.put(overworld, new Drawn(wall, Look.FIELD));
            }
        }
        return new Read(Map.copyOf(walls), spreading, overworldEvaluated);
    }

    private static Map<String, List<Area>> regionsOf(final CustomBorderSpec.World world) {
        final Map<String, List<Area>> regions = new LinkedHashMap<>();
        for (final CustomBorderSpec.Polygon polygon : world.polygons()) {
            // Fewer than three corners enclose nothing; the group's check refuses them, this keeps a read safe.
            if (polygon.corners().size() >= 3) {
                regions.computeIfAbsent(polygon.name(), ignored -> new ArrayList<>())
                        .add(new Area(polygon.corners().stream()
                                .map(corner -> new Area.Point(corner.x() + 0.5, corner.z() + 0.5))
                                .toList()));
            }
        }
        return regions;
    }

    /**
     * The walls of every world.
     *
     * @param spreading the overworld's openings whose front has not reached all of their regions yet
     * @param overworldEvaluated whether the overworld was enabled and evaluated in this read
     */
    record Read(Map<String, Drawn> walls, List<Front.Stage> spreading, boolean overworldEvaluated) {}

    /** The wall of one world and how it is drawn. */
    record Drawn(Wall wall, Look look) {}
}
