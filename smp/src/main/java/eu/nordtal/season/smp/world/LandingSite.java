package eu.nordtal.season.smp.world;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * Finds a spot in a world where a player can stand safely.
 *
 * It searches a square spiral outward from a point for safe ground under two blocks of air. Main thread only.
 */
public final class LandingSite {

    private static final int MAX_RADIUS = 256;

    private LandingSite() {}

    /** Returns the first safe spot near the world centre, or the world spawn when the search finds none. */
    public static Location find(final World world) {
        final Location found = find(world, 0, 0);
        return found == null ? world.getSpawnLocation() : found;
    }

    /** Returns the first safe spot around a column, or null when none lies within {@link #MAX_RADIUS}. */
    public static @Nullable Location find(final World world, final int centreX, final int centreZ) {
        for (int radius = 0; radius <= MAX_RADIUS; radius += 4) {
            for (int dx = -radius; dx <= radius; dx += 4) {
                for (int dz = -radius; dz <= radius; dz += 4) {
                    // Only the ring, because smaller radii are already searched.
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    final Location candidate = safeColumn(world, centreX + dx, centreZ + dz);
                    if (candidate != null) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Returns a spot as close to {@code preferred} as possible.
     *
     * The result is {@code preferred} when it is safe, else the nearest safe column, else {@code preferred}.
     */
    public static Location safeAt(final World world, final Location preferred) {
        return findSafeAt(world, preferred).orElse(preferred);
    }

    /**
     * Returns the same spot as the search above, or empty when no column within {@link #MAX_RADIUS} fits.
     */
    public static java.util.Optional<Location> findSafeAt(final World world, final Location preferred) {
        if (fits(world, preferred)) {
            return java.util.Optional.of(preferred);
        }
        return java.util.Optional.ofNullable(find(world, preferred.getBlockX(), preferred.getBlockZ()));
    }

    /** Returns whether two air blocks stand over solid ground at this spot. A liquid never fits. */
    private static boolean fits(final World world, final Location at) {
        final Block feet = world.getBlockAt(at.getBlockX(), at.getBlockY(), at.getBlockZ());
        final Block head = feet.getRelative(0, 1, 0);
        final Block below = feet.getRelative(0, -1, 0);
        return feet.getType().isAir() && head.getType().isAir() && isGoodGround(below.getType());
    }

    private static @Nullable Location safeColumn(final World world, final int x, final int z) {
        final Block highest = world.getHighestBlockAt(x, z);
        // An empty column has bedrock at the world floor, which is no site.
        if (highest.getY() <= world.getMinHeight() + 4) {
            return null;
        }
        if (!isGoodGround(highest.getType())) {
            return null;
        }
        final Block above = highest.getRelative(0, 1, 0);
        final Block head = highest.getRelative(0, 2, 0);
        if (!above.getType().isAir() || !head.getType().isAir()) {
            return null;
        }
        // The centre of the block, facing south.
        return new Location(world, x + 0.5, highest.getY() + 1, z + 0.5);
    }

    private static boolean isGoodGround(final Material material) {
        if (!material.isSolid()) {
            return false;
        }
        return switch (material) {
            case LAVA,
                    MAGMA_BLOCK,
                    FIRE,
                    SOUL_FIRE,
                    CAMPFIRE,
                    SOUL_CAMPFIRE,
                    POWDER_SNOW,
                    CACTUS,
                    SWEET_BERRY_BUSH,
                    WITHER_ROSE,
                    POINTED_DRIPSTONE -> false;
            default -> true;
        };
    }
}
