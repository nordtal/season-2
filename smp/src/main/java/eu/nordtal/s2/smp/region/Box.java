package eu.nordtal.s2.smp.region;

/**
 * One axis-aligned box in one world, inclusive on both corners.
 *
 * <p>The shape behind both {@code spawn-regions} and {@code balloons}: a spawn is a box you may not
 * build in, a balloon is a box that opens a GUI. Neither needs claims, flags or ownership, which is
 * why there is no region system here.
 *
 * @param world the world name this box is in
 * @param minX  lowest x, inclusive
 * @param minY  lowest y, inclusive
 * @param minZ  lowest z, inclusive
 * @param maxX  highest x, inclusive
 * @param maxY  highest y, inclusive
 * @param maxZ  highest z, inclusive
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

    /** Whether the given block position is inside this box. Both corners count as inside. */
    public boolean contains(final String world, final int x, final int y, final int z) {
        return this.world.equals(world)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    /**
     * The distance from a point on the horizontal plane to this box's centre, ignoring height.
     *
     * <p>Nordtal's balloon must stand outside radius 10 and inside radius 21.5 of the border centre:
     * that is what makes the opening border of 20 withhold travel and the first expansion to 43
     * hand it over.
     */
    public double horizontalDistanceFrom(final double centreX, final double centreZ) {
        final double dx = (minX + maxX) / 2.0 - centreX;
        final double dz = (minZ + maxZ) / 2.0 - centreZ;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
