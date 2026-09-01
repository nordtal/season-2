package eu.nordtal.s2.smp.farm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Finds somewhere in a freshly generated farm world that a person can be put down without dying.
 *
 * <p>A new seed every day means the point 0/0 is a lottery: it can be the middle of a lava lake, the
 * roof of a ravine, or a hundred blocks of ocean. docs/smp.md#spawns says the farm world's arrival
 * point is found programmatically for exactly that reason, and this is that search - a square
 * spiral outwards from the border centre, taking the first column with solid ground, two blocks of
 * air above it and nothing dangerous underfoot.
 *
 * <p>Runs on the main thread against a world that Chunky has already filled, so every chunk it
 * touches is on disk and no generation happens here.
 */
public final class LandingSite {

    /** How far out to look before giving up. Well inside the farm world's 2000-block border. */
    private static final int MAX_RADIUS = 256;

    private LandingSite() {
    }

    /**
     * The first safe spot at or near the world centre.
     *
     * <p>Falls back to the world's own spawn when the search finds nothing, which on a normal
     * overworld seed does not happen - and if it ever does, an unsafe arrival is still better than
     * a farm world nobody can enter.
     */
    public static Location find(final World world) {
        for (int radius = 0; radius <= MAX_RADIUS; radius += 4) {
            for (int dx = -radius; dx <= radius; dx += 4) {
                for (int dz = -radius; dz <= radius; dz += 4) {
                    // Only the ring, not the filled square - the inside was covered by a smaller
                    // radius already.
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    final Location candidate = safeColumn(world, dx, dz);
                    if (candidate != null) {
                        return candidate;
                    }
                }
            }
        }
        return world.getSpawnLocation();
    }

    private static Location safeColumn(final World world, final int x, final int z) {
        final Block highest = world.getHighestBlockAt(x, z);
        if (!isGoodGround(highest.getType())) {
            return null;
        }
        final Block above = highest.getRelative(0, 1, 0);
        final Block head = highest.getRelative(0, 2, 0);
        if (!above.getType().isAir() || !head.getType().isAir()) {
            return null;
        }
        // Centre of the block, looking south, so nobody lands inside a wall corner.
        return new Location(world, x + 0.5, highest.getY() + 1, z + 0.5);
    }

    private static boolean isGoodGround(final Material material) {
        if (!material.isSolid()) {
            return false;
        }
        return switch (material) {
            case LAVA, MAGMA_BLOCK, FIRE, SOUL_FIRE, CAMPFIRE, SOUL_CAMPFIRE, POWDER_SNOW,
                 CACTUS, SWEET_BERRY_BUSH, WITHER_ROSE, POINTED_DRIPSTONE -> false;
            default -> true;
        };
    }
}
