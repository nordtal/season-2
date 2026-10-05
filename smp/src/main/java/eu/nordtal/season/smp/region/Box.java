package eu.nordtal.season.smp.region;

/**
 * One axis-aligned box in one world, inclusive on both corners.
 *
 * It backs both {@code spawn-regions} and {@code balloons}, neither of which needs claims or ownership.
 */
public record Box(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public Box {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("a box needs a world");
        }
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            throw new IllegalArgumentException(
                    "the box in '" + world + "' has a max corner that is not above its min corner");
        }
    }

    /** Whether the given block position is inside this box, corners included. */
    public boolean contains(final String world, final int x, final int y, final int z) {
        return this.world.equals(world) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /**
     * The horizontal distance from a point to this box's centre, ignoring height.
     *
     * Balloon placement uses it: outside radius 10 and inside 21.5 of the border centre.
     */
    public double horizontalDistanceFrom(final double centreX, final double centreZ) {
        final double dx = (minX + maxX) / 2.0 - centreX;
        final double dz = (minZ + maxZ) / 2.0 - centreZ;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
