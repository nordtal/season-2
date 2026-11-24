package eu.nordtal.season.smp.wall;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntPredicate;
import org.bukkit.HeightMap;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

/** The ground under each panel, read from the world on the inside of the edge and kept for a few seconds. */
final class Grounds {

    /** How long a known ground is used before it is read again, in milliseconds; an unknown one is read each time. */
    static final long FRESH = 5000;

    /** Radius around a placed or broken block within which cached panel grounds are invalidated, in blocks. */
    static final double INVALIDATE_RADIUS = 4.0;

    private final Map<String, Map<Panel, Read>> worlds = new HashMap<>();

    /**
     * The ground height for a panel, or {@link Band#UNKNOWN} while a chunk there is not loaded.
     * In a world with a ceiling, scans downward from {@code fromY} instead of reading the roof.
     */
    int at(final World world, final Panel panel, final int fromY, final long now) {
        final Map<Panel, Read> reads = worlds.computeIfAbsent(world.getName(), ignored -> new HashMap<>());
        final int height = world.hasCeiling() ? fromY : 0;
        final Read before = reads.get(panel);
        if (before != null && before.fromY() == height && now - before.at() < FRESH) {
            return before.ground();
        }
        final int ground = read(world, panel, fromY);
        if (ground != Band.UNKNOWN) {
            reads.put(panel, new Read(ground, height, now));
        }
        return ground;
    }

    /** Clears cached grounds near a block change so player-built pillars update immediately. */
    void invalidate(final String world, final int blockX, final int blockZ) {
        final Map<Panel, Read> reads = worlds.get(world);
        if (reads == null || reads.isEmpty()) {
            return;
        }
        final double cx = blockX + 0.5;
        final double cz = blockZ + 0.5;
        reads.keySet().removeIf(panel -> panel.distance(cx, cz) <= INVALIDATE_RADIUS);
    }

    void clear() {
        worlds.clear();
    }

    void clear(final String world) {
        worlds.remove(world);
    }

    private static int read(final World world, final Panel panel, final int fromY) {
        return surfaceAt(panel, (x, z) -> surface(world, x, z, fromY));
    }

    /** Higher surface at 0.5 and 1.5 blocks inside the edge, or {@link Band#UNKNOWN} when either chunk is unloaded. */
    static int surfaceAt(final Panel panel, final SurfaceReader reader) {
        final double yaw = Math.toRadians(panel.yaw());
        // Panel yaw is oriented so (-sin(yaw), cos(yaw)) points inward from the edge.
        final double inwardX = -Math.sin(yaw);
        final double inwardZ = Math.cos(yaw);
        final int edge = reader.surface(panel.x() + inwardX * 0.5, panel.z() + inwardZ * 0.5);
        if (edge == Band.UNKNOWN) {
            return Band.UNKNOWN;
        }
        final int beside = reader.surface(panel.x() + inwardX * 1.5, panel.z() + inwardZ * 1.5);
        if (beside == Band.UNKNOWN) {
            return Band.UNKNOWN;
        }
        return Math.max(edge, beside);
    }

    @FunctionalInterface
    interface SurfaceReader {

        int surface(double x, double z);
    }

    private static int surface(final World world, final double x, final double z, final int fromY) {
        final int blockX = (int) Math.floor(x);
        final int blockZ = (int) Math.floor(z);
        if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
            return Band.UNKNOWN;
        }
        if (world.hasCeiling()) {
            return floorFrom(
                    fromY,
                    world.getMinHeight(),
                    world.getMaxHeight(),
                    y -> blocksSurface(world.getBlockAt(blockX, y, blockZ)));
        }
        return world.getHighestBlockYAt(blockX, blockZ, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
    }

    /**
     * First surface y at or below {@code fromY} in a ceiling world, or {@code minY} when nothing holds below.
     */
    static int floorFrom(final int fromY, final int minY, final int maxY, final IntPredicate solid) {
        final int start = Math.clamp(fromY, minY, maxY - 1);
        for (int y = start; y >= minY; y--) {
            if (solid.test(y)) {
                return y + 1;
            }
        }
        return minY;
    }

    private static boolean blocksSurface(final Block block) {
        return block.isLiquid() || (!block.isPassable() && !Tag.LEAVES.isTagged(block.getType()));
    }

    private record Read(int ground, int fromY, long at) {}
}
