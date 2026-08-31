package eu.nordtal.s2.hungergames.game;

import java.util.ArrayList;
import java.util.List;

/**
 * Procedural spawn tower positions: {@code count} points arranged in a circle around the centre at
 * equal distance, per docs/hunger-games.md#start ("arranged in a circle around the spawn at equal
 * distance from the centre loot"). Pure X/Z math with no Bukkit dependency, so a Location is built
 * from these by the caller.
 */
public final class SpawnTowers {

    private SpawnTowers() {
    }

    /**
     * @param count      how many towers are needed; must be positive
     * @param centreX    the circle's centre
     * @param centreZ    the circle's centre
     * @param radius     distance from the centre to each tower; must be positive
     * @return {@code count} {@code [x, z]} pairs, evenly spaced starting at angle 0 (positive X axis)
     * @throws IllegalArgumentException if {@code count} or {@code radius} is not positive
     */
    public static List<double[]> positions(final int count, final double centreX, final double centreZ,
                                            final double radius) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive, was " + count);
        }
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be positive, was " + radius);
        }

        final List<double[]> positions = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            final double angle = 2 * Math.PI * index / count;
            final double x = centreX + radius * Math.cos(angle);
            final double z = centreZ + radius * Math.sin(angle);
            positions.add(new double[] {x, z});
        }
        return positions;
    }
}
