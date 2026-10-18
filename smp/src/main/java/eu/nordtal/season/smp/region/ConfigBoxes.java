package eu.nordtal.season.smp.region;

import eu.nordtal.season.smp.config.SmpSpec;
import eu.nordtal.season.smp.config.SpawnRegionSpec;
import java.util.ArrayList;
import java.util.List;

public final class ConfigBoxes {

    private ConfigBoxes() {}

    public static Boxes spawnRegions(final SmpSpec config) {
        final List<Box> boxes = new ArrayList<>();
        for (final SpawnRegionSpec region : config.spawnRegions()) {
            boxes.add(new Box(
                    region.world(),
                    region.minX(),
                    region.minY(),
                    region.minZ(),
                    region.maxX(),
                    region.maxY(),
                    region.maxZ()));
        }
        return new Boxes(boxes);
    }

    public static Boxes wheelRegions(final SmpSpec config) {
        final List<Box> boxes = new ArrayList<>();
        for (final SpawnRegionSpec region : config.wheelRegions()) {
            boxes.add(new Box(
                    region.world(),
                    region.minX(),
                    region.minY(),
                    region.minZ(),
                    region.maxX(),
                    region.maxY(),
                    region.maxZ()));
        }
        return new Boxes(boxes);
    }
}
