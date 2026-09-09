package eu.nordtal.s2.smp.farm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Finds somewhere in a freshly generated farm world that a person can be put down without dying.
 *
 * <p>A new seed every day means the point 0/0 is a lottery - a lava lake, the roof of a ravine, a
 * hundred blocks of ocean - so the arrival point is searched for: a square spiral outwards from the
 * border centre, taking the first column with solid ground, two blocks of air above it and nothing
 * dangerous underfoot.
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
     * <p>Falls back to the world's own spawn when the search finds nothing: an unsafe arrival is
     * still better than a farm world nobody can enter.
     */
    public static Location find(final World world) {
        final Location found = find(world, 0, 0);
        return found == null ? world.getSpawnLocation() : found;
    }

    /**
     * The same search, around a point of the caller's choosing.
     *
     * @param world   the world
     * @param centreX the column to start from
     * @param centreZ the column to start from
     * @return the first safe column, or {@code null} if none was found within {@link #MAX_RADIUS}
     */
    public static Location find(final World world, final int centreX, final int centreZ) {
        for (int radius = 0; radius <= MAX_RADIUS; radius += 4) {
            for (int dx = -radius; dx <= radius; dx += 4) {
                for (int dz = -radius; dz <= radius; dz += 4) {
                    // Only the ring, not the filled square - the inside was covered by a smaller
                    // radius already.
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
     * A spot a player can be put down at, as close to {@code preferred} as possible.
     *
     * <p>The preferred spot wins whenever it is habitable: a built spawn is a decision somebody
     * took, and moving a player off it is worse than landing them on a slab this check happens to
     * dislike. Only when it is uninhabitable does this search outwards from that column.</p>
     *
     * @param world     the world
     * @param preferred where the caller would like them
     * @return {@code preferred} if a player fits there, the nearest column where one does
     *         otherwise, and {@code preferred} again if the search finds nothing
     */
    public static Location safeAt(final World world, final Location preferred) {
        return findSafeAt(world, preferred).orElse(preferred);
    }

    /**
     * The same search, for a caller that is allowed to say no.
     *
     * <p>{@link #safeAt}'s fallback to {@code preferred} is right where not arriving is worse than
     * arriving badly - a duel has to end, the farm-world reset has to empty a world. The balloon is
     * the one caller where it is not: it already has a "destination unavailable" branch, and its
     * success path tells the player they arrived somewhere they may not survive.</p>
     *
     * @param world     the world
     * @param preferred where the caller would like them
     * @return {@code preferred} if a player fits there, the nearest column where one does
     *         otherwise, and empty if there is no such column within {@link #MAX_RADIUS}
     */
    public static java.util.Optional<Location> findSafeAt(final World world,
                                                          final Location preferred) {
        if (fits(world, preferred)) {
            return java.util.Optional.of(preferred);
        }
        return java.util.Optional.ofNullable(
                find(world, preferred.getBlockX(), preferred.getBlockZ()));
    }

    /**
     * Whether two <b>air</b> blocks stand at this spot with ground worth standing on underneath.
     *
     * <p>Air rather than "passable", and {@link #isGoodGround} rather than "solid": a liquid is
     * passable, so a world spawn sitting in lava would pass the loose test.</p>
     */
    private static boolean fits(final World world, final Location at) {
        final Block feet = world.getBlockAt(at.getBlockX(), at.getBlockY(), at.getBlockZ());
        final Block head = feet.getRelative(0, 1, 0);
        final Block below = feet.getRelative(0, -1, 0);
        return feet.getType().isAir() && head.getType().isAir() && isGoodGround(below.getType());
    }

    private static Location safeColumn(final World world, final int x, final int z) {
        final Block highest = world.getHighestBlockAt(x, z);
        // A column with nothing in it answers with the block at the bottom of the world, and
        // bedrock is solid and on no exclusion list - so without this the search reports "safe
        // ground" at the world floor. That is the absence of a landing site, not one.
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
