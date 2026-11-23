package eu.nordtal.season.smp.wall;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.HeightMap;
import org.bukkit.World;

/** The ground under each panel, read from the world on the inside of the edge and kept for a few seconds. */
final class Grounds {

    /** How long a known ground is used before it is read again, in milliseconds; an unknown one is read each time. */
    static final long FRESH = 5000;

    /** Radius around a placed or broken block within which cached panel grounds are invalidated, in blocks. */
    private static final double INVALIDATE_RADIUS = 4.0;

    private final Map<String, Map<Panel, Read>> worlds = new HashMap<>();

    /**
     * The ground height for a panel under {@code placement}, or {@link Band#UNKNOWN} while a chunk there is not loaded.
     */
    int at(final World world, final Panel panel, final Placement placement, final long now) {
        final Map<Panel, Read> reads = worlds.computeIfAbsent(world.getName(), ignored -> new HashMap<>());
        final Read before = reads.get(panel);
        if (before != null && before.placement() == placement && now - before.at() < FRESH) {
            return before.ground();
        }
        final int ground = read(world, panel, placement);
        if (ground != Band.UNKNOWN) {
            reads.put(panel, new Read(ground, placement, now));
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

    private static int read(final World world, final Panel panel, final Placement placement) {
        final double yaw = Math.toRadians(panel.yaw());
        // Panel yaw is oriented so (-sin(yaw), cos(yaw)) points inward from the edge.
        final double inwardX = -Math.sin(yaw);
        final double inwardZ = Math.cos(yaw);
        final int edge = surface(world, panel.x() + inwardX * 0.5, panel.z() + inwardZ * 0.5);
        if (edge == Band.UNKNOWN) {
            return Band.UNKNOWN;
        }
        if (placement == Placement.PROXIMITY_WINDOW) {
            final int beside = surface(world, panel.x() + inwardX * 1.5, panel.z() + inwardZ * 1.5);
            if (beside == Band.UNKNOWN) {
                return Band.UNKNOWN;
            }
            return Math.max(edge, beside);
        }
        return edge;
    }

    private static int surface(final World world, final double x, final double z) {
        final int blockX = (int) Math.floor(x);
        final int blockZ = (int) Math.floor(z);
        if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
            return Band.UNKNOWN;
        }
        return world.getHighestBlockYAt(blockX, blockZ, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
    }

    private record Read(int ground, Placement placement, long at) {}
}
