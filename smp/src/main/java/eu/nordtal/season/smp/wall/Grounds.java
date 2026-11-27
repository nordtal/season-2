package eu.nordtal.season.smp.wall;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntPredicate;
import org.bukkit.HeightMap;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * The ground under each panel and how far the wall rises over what stands on it, kept for a few seconds.
 * The ground is the lowest surface around the panel, so a pillar, a wall or a line of blocks overhead leaves it
 * where it is. Each of those raises the wall's upper end in a bell around it.
 */
final class Grounds {

    /** How long a known ground is used before it is read again, in milliseconds; an unknown one is read each time. */
    static final long FRESH = 5000;

    /** How far from a placed or broken block the surfaces read for a panel may have changed, in blocks. */
    static final double INVALIDATE_RADIUS = 4.0;

    /** How far along the wall a panel's ground is the lowest surface, in blocks. */
    static final double FOOT = 8;

    /** The narrowest and the widest bell, in blocks to each side: a bell is twice as wide as its obstacle is high. */
    static final double NARROW = 6;

    static final double SPREAD = 16;

    private final Map<String, Map<Panel, Read>> surfaces = new HashMap<>();
    private final Map<String, Map<Panel, Shaped>> worlds = new HashMap<>();

    /**
     * The ground for a panel of {@code wall}, or {@link Band.Ground#UNKNOWN} while a chunk there is not loaded.
     * In a world with a ceiling, scans downward from {@code fromY} instead of reading the roof.
     */
    Band.Ground at(final World world, final Wall wall, final Panel panel, final int fromY, final long now) {
        final Map<Panel, Shaped> shapes = worlds.computeIfAbsent(world.getName(), ignored -> new HashMap<>());
        final int height = world.hasCeiling() ? fromY : 0;
        final Shaped before = shapes.get(panel);
        if (before != null && before.fromY() == height && now - before.at() < FRESH) {
            return before.ground();
        }
        final Map<Panel, Read> reads = surfaces.computeIfAbsent(world.getName(), ignored -> new HashMap<>());
        final Band.Ground ground = shape(panel, wall.near(panel.x(), panel.z(), SPREAD), near -> {
            final Read read = reads.get(near);
            if (read != null && read.fromY() == height && now - read.at() < FRESH) {
                return read.surface();
            }
            final Surface surface = surfaceAt(near, (x, z) -> surface(world, x, z, fromY));
            if (surface != null) {
                reads.put(near, new Read(surface, height, now));
            }
            return surface;
        });
        if (!ground.equals(Band.Ground.UNKNOWN)) {
            shapes.put(panel, new Shaped(ground, height, now));
        }
        return ground;
    }

    /** Forgets what a block change at {@code blockX}, {@code blockZ} may have changed. */
    void invalidate(final String world, final int blockX, final int blockZ) {
        final double cx = blockX + 0.5;
        final double cz = blockZ + 0.5;
        final Map<Panel, Read> reads = surfaces.get(world);
        if (reads != null) {
            reads.keySet().removeIf(panel -> panel.distance(cx, cz) <= INVALIDATE_RADIUS);
        }
        final Map<Panel, Shaped> shapes = worlds.get(world);
        if (shapes != null) {
            shapes.keySet().removeIf(panel -> panel.distance(cx, cz) <= INVALIDATE_RADIUS + SPREAD);
        }
    }

    void clear() {
        surfaces.clear();
        worlds.clear();
    }

    void clear(final String world) {
        surfaces.remove(world);
        worlds.remove(world);
    }

    /**
     * The ground of {@code panel} among the panels {@code near} it, or {@link Band.Ground#UNKNOWN} without its surface.
     * The foot is the lowest surface within {@link #FOOT} blocks, and every higher surface nearby raises the wall by
     * a bell twice as wide as that surface is high, so the wall climbs an obstacle from both sides.
     */
    static Band.Ground shape(
            final Panel panel, final Collection<Panel> near, final Function<Panel, @Nullable Surface> surfaces) {
        final Surface own = surfaces.apply(panel);
        if (own == null) {
            return Band.Ground.UNKNOWN;
        }
        final Map<Panel, Surface> known = new HashMap<>();
        int foot = own.low();
        for (final Panel other : near) {
            final Surface surface = other.equals(panel) ? own : surfaces.apply(other);
            if (surface != null) {
                known.put(other, surface);
                if (panel.distance(other.x(), other.z()) <= FOOT) {
                    foot = Math.min(foot, surface.low());
                }
            }
        }
        double top = own.high();
        for (final Map.Entry<Panel, Surface> other : known.entrySet()) {
            final double over = other.getValue().high() - foot;
            final double width = Math.clamp(2 * over, NARROW, SPREAD);
            final double away =
                    panel.distance(other.getKey().x(), other.getKey().z()) / width;
            if (over > 0 && away < 1) {
                top = Math.max(top, foot + over * (1 + Math.cos(Math.PI * away)) / 2);
            }
        }
        final int upper = (int) Math.round(top);
        // A wall higher than the tint carries keeps its upper end, where a player on the obstacle stands.
        return new Band.Ground(Math.max(foot, upper - Band.HIGHEST_RISE), Math.min(upper - foot, Band.HIGHEST_RISE));
    }

    /**
     * The surfaces at a panel: 0.5 and 1.5 blocks inside the edge and 0.5 outside, where no player builds.
     * Null when a chunk there is not loaded.
     */
    static @Nullable Surface surfaceAt(final Panel panel, final SurfaceReader reader) {
        final double yaw = Math.toRadians(panel.yaw());
        // Panel yaw is oriented so (-sin(yaw), cos(yaw)) points inward from the edge.
        final double inwardX = -Math.sin(yaw);
        final double inwardZ = Math.cos(yaw);
        final int[] read = new int[3];
        final double[] inside = {0.5, 1.5, -0.5};
        for (int i = 0; i < inside.length; i++) {
            read[i] = reader.surface(panel.x() + inwardX * inside[i], panel.z() + inwardZ * inside[i]);
            if (read[i] == UNLOADED) {
                return null;
            }
        }
        return new Surface(Math.max(read[0], read[1]), Math.min(Math.min(read[0], read[1]), read[2]));
    }

    /** What a {@link SurfaceReader} returns where the chunk is not loaded. */
    static final int UNLOADED = Integer.MIN_VALUE;

    @FunctionalInterface
    interface SurfaceReader {

        int surface(double x, double z);
    }

    private static int surface(final World world, final double x, final double z, final int fromY) {
        final int blockX = (int) Math.floor(x);
        final int blockZ = (int) Math.floor(z);
        if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
            return UNLOADED;
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

    /** The surfaces at one panel: the higher of the two inside the edge, and the lowest of all. */
    record Surface(int high, int low) {}

    private record Read(Surface surface, int fromY, long at) {}

    private record Shaped(Band.Ground ground, int fromY, long at) {}
}
