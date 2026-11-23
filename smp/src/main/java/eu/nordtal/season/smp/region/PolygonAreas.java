package eu.nordtal.season.smp.region;

import java.util.List;
import java.util.Optional;

/**
 * An ordered list of {@link PolygonArea} entries.
 * The first area in configuration order that holds a position wins.
 */
public final class PolygonAreas {

    private final List<PolygonArea> areas;

    public PolygonAreas(final List<PolygonArea> areas) {
        this.areas = List.copyOf(areas);
    }

    public Optional<PolygonArea> atBlock(final String world, final int x, final int y, final int z) {
        for (final PolygonArea area : areas) {
            if (area.containsBlock(world, x, y, z)) {
                return Optional.of(area);
            }
        }
        return Optional.empty();
    }

    public boolean containsBlock(final String world, final int x, final int y, final int z) {
        return atBlock(world, x, y, z).isPresent();
    }

    public Optional<PolygonArea> atPoint(final String world, final double x, final double y, final double z) {
        for (final PolygonArea area : areas) {
            if (area.containsPoint(world, x, y, z)) {
                return Optional.of(area);
            }
        }
        return Optional.empty();
    }

    public boolean containsPoint(final String world, final double x, final double y, final double z) {
        return atPoint(world, x, y, z).isPresent();
    }

    public List<PolygonArea> all() {
        return areas;
    }

    public boolean isEmpty() {
        return areas.isEmpty();
    }
}
