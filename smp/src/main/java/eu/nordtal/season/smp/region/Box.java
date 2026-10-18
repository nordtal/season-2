package eu.nordtal.season.smp.region;

/**
 * An axis-aligned box in one world. Both corners are inside the box.
 *
 * It backs {@code spawn-regions} and {@code wheel-regions}.
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

    public boolean contains(final String world, final int x, final int y, final int z) {
        return this.world.equals(world) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
}
