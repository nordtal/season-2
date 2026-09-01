package eu.nordtal.s2.smp.region;

import eu.nordtal.s2.smp.config.SmpSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the two box lists in {@code config.yml} into {@link Boxes}.
 *
 * <p>Its own class so that {@link Box} and {@link Boxes} stay free of the config system and can be
 * tested as plain values - the conversion is the only place the two worlds meet.
 */
public final class ConfigBoxes {

    private ConfigBoxes() {
    }

    public static Boxes spawnRegions(final SmpSpec config) {
        final List<Box> boxes = new ArrayList<>();
        for (final SmpSpec.SpawnRegionSpec region : config.spawnRegions()) {
            boxes.add(new Box(region.world(), region.minX(), region.minY(), region.minZ(),
                    region.maxX(), region.maxY(), region.maxZ()));
        }
        return new Boxes(boxes);
    }

    public static Boxes balloons(final SmpSpec config) {
        final List<Box> boxes = new ArrayList<>();
        for (final SmpSpec.BalloonSpec balloon : config.balloons()) {
            boxes.add(new Box(balloon.world(), balloon.minX(), balloon.minY(), balloon.minZ(),
                    balloon.maxX(), balloon.maxY(), balloon.maxZ()));
        }
        return new Boxes(boxes);
    }
}
