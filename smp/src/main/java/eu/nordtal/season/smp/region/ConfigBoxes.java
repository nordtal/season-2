package eu.nordtal.season.smp.region;

import eu.nordtal.season.smp.config.SpawnRegionSpec;
import java.util.ArrayList;
import java.util.List;

public final class ConfigBoxes {

    private ConfigBoxes() {}

    /** Returns the boxes of {@code regions} in their order. */
    public static Boxes of(final List<SpawnRegionSpec> regions) {
        final List<Box> boxes = new ArrayList<>();
        for (final SpawnRegionSpec region : regions) {
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
