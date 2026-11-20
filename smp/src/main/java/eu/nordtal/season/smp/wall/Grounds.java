package eu.nordtal.season.smp.wall;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.HeightMap;
import org.bukkit.World;

/** The ground under each panel, read from the world on both sides of the edge and kept for a few seconds. */
final class Grounds {

    /** How long a read ground is used before it is read again, in milliseconds. */
    static final long FRESH = 5000;

    private final Map<String, Map<Panel, Read>> worlds = new HashMap<>();

    /** The lower ground on either side of the panel, or {@link Band#UNKNOWN} while a chunk there is not loaded. */
    int at(final World world, final Panel panel, final long now) {
        final Map<Panel, Read> reads = worlds.computeIfAbsent(world.getName(), ignored -> new HashMap<>());
        final Read before = reads.get(panel);
        if (before != null && now - before.at() < FRESH) {
            return before.ground();
        }
        final int ground = read(world, panel);
        reads.put(panel, new Read(ground, now));
        return ground;
    }

    void clear() {
        worlds.clear();
    }

    private static int read(final World world, final Panel panel) {
        final double yaw = Math.toRadians(panel.yaw());
        // The edge runs along the yaw's cosine and sine, so this points half a block across it.
        final double normalX = -Math.sin(yaw) * 0.5;
        final double normalZ = Math.cos(yaw) * 0.5;
        final int front = surface(world, panel.x() + normalX, panel.z() + normalZ);
        final int back = surface(world, panel.x() - normalX, panel.z() - normalZ);
        return front == Band.UNKNOWN || back == Band.UNKNOWN ? Band.UNKNOWN : Math.min(front, back);
    }

    private static int surface(final World world, final double x, final double z) {
        final int blockX = (int) Math.floor(x);
        final int blockZ = (int) Math.floor(z);
        if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
            return Band.UNKNOWN;
        }
        return world.getHighestBlockYAt(blockX, blockZ, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
    }

    private record Read(int ground, long at) {}
}
