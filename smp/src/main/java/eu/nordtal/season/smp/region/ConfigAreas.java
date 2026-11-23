package eu.nordtal.season.smp.region;

import eu.nordtal.season.database.inbox.AreaShape.Corner;
import eu.nordtal.season.smp.config.DuelAreaSpec;
import eu.nordtal.season.smp.config.SpawnRegionSpec;
import java.util.ArrayList;
import java.util.List;

/** Reads configured 3D polygon lists into {@link PolygonAreas}. */
public final class ConfigAreas {

    private ConfigAreas() {}

    public static PolygonAreas ofSpawn(final List<SpawnRegionSpec> regions) {
        final List<PolygonArea> areas = new ArrayList<>();
        for (final SpawnRegionSpec region : regions) {
            if (region.corners().size() < 3
                    || region.minY() > region.maxY()
                    || region.world().isBlank()) {
                continue;
            }
            areas.add(new PolygonArea(
                    region.name(),
                    region.world(),
                    region.corners().stream()
                            .map(corner -> new Corner(corner.x(), corner.z()))
                            .toList(),
                    region.minY(),
                    region.maxY()));
        }
        return new PolygonAreas(areas);
    }

    public static PolygonAreas ofDuel(final List<DuelAreaSpec> regions) {
        final List<PolygonArea> areas = new ArrayList<>();
        for (final DuelAreaSpec region : regions) {
            if (region.corners().size() < 3
                    || region.minY() > region.maxY()
                    || region.world().isBlank()) {
                continue;
            }
            areas.add(new PolygonArea(
                    region.name(),
                    region.world(),
                    region.corners().stream()
                            .map(corner -> new Corner(corner.x(), corner.z()))
                            .toList(),
                    region.minY(),
                    region.maxY()));
        }
        return new PolygonAreas(areas);
    }
}
