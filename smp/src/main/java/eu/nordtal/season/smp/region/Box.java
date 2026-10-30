package eu.nordtal.season.smp.region;

/**
 * An axis-aligned box in one world. Both corners are inside the box.
 *
 * It backs {@code spawn-regions}, {@code wheel-regions} and the areas of the duels.
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

    /** Returns the box between two corners, whichever of them is lower on each axis. */
    public static Box spanning(
            final String world, final int x1, final int y1, final int z1, final int x2, final int y2, final int z2) {
        return new Box(
                world,
                Math.min(x1, x2),
                Math.min(y1, y2),
                Math.min(z1, z2),
                Math.max(x1, x2),
                Math.max(y1, y2),
                Math.max(z1, z2));
    }

    public boolean contains(final String world, final int x, final int y, final int z) {
        return this.world.equals(world) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
}
