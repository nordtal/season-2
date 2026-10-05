package eu.nordtal.season.smp.world;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * Finds somewhere in a world that a person can be put down without dying.
 *
 * Searches a square spiral out from a point for safe ground under two blocks of air, on the main thread.
 */
public final class LandingSite {

    /** How far out to look before giving up. Well inside any world border this server sets. */
    private static final int MAX_RADIUS = 256;

    private LandingSite() {}

    /** The first safe spot at or near the world centre, or the world spawn when the search finds nothing. */
    public static Location find(final World world) {
        final Location found = find(world, 0, 0);
        return found == null ? world.getSpawnLocation() : found;
    }

    /** The same search around a given column, or null when nothing is found within {@link #MAX_RADIUS}. */
    public static @Nullable Location find(final World world, final int centreX, final int centreZ) {
        for (int radius = 0; radius <= MAX_RADIUS; radius += 4) {
            for (int dx = -radius; dx <= radius; dx += 4) {
                for (int dz = -radius; dz <= radius; dz += 4) {
                    // Only the ring: smaller radii already covered the inside.
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
     * {@code preferred} itself when habitable, else the nearest safe column, else {@code preferred} again.
     */
    public static Location safeAt(final World world, final Location preferred) {
        return findSafeAt(world, preferred).orElse(preferred);
    }

    /**
     * The same search, for a caller that is allowed to say no: empty when no column within {@link #MAX_RADIUS} fits.
     */
    public static java.util.Optional<Location> findSafeAt(final World world, final Location preferred) {
        if (fits(world, preferred)) {
            return java.util.Optional.of(preferred);
        }
        return java.util.Optional.ofNullable(find(world, preferred.getBlockX(), preferred.getBlockZ()));
    }

    /** Whether two air blocks stand at this spot over good ground; a liquid is passable but never fits. */
    private static boolean fits(final World world, final Location at) {
        final Block feet = world.getBlockAt(at.getBlockX(), at.getBlockY(), at.getBlockZ());
        final Block head = feet.getRelative(0, 1, 0);
        final Block below = feet.getRelative(0, -1, 0);
        return feet.getType().isAir() && head.getType().isAir() && isGoodGround(below.getType());
    }

    private static @Nullable Location safeColumn(final World world, final int x, final int z) {
        final Block highest = world.getHighestBlockAt(x, z);
        // An empty column would otherwise answer with solid bedrock at the world floor, which is the absence of a site.
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
