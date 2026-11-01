package eu.nordtal.season.smp.rail;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * The one holder of the plugin's chunk tickets, which counts who holds each chunk.
 * Paper keeps one ticket per plugin and chunk, so a rail walk and a ride that both hold a chunk share it here,
 * and the ticket goes only when the last of them lets go.
 */
public final class ChunkTickets {

    private final Plugin plugin;
    private final Map<String, Map<Long, Integer>> holds = new HashMap<>();

    public ChunkTickets(final Plugin plugin) {
        this.plugin = plugin;
    }

    /** Holds the chunk at {@code chunkX}, {@code chunkZ} loaded once more. */
    void hold(final World world, final int chunkX, final int chunkZ) {
        final Map<Long, Integer> held = holds.computeIfAbsent(world.getName(), _ -> new HashMap<>());
        final long key = Chunk.getChunkKey(chunkX, chunkZ);
        if (!held.containsKey(key)) {
            world.addPluginChunkTicket(chunkX, chunkZ, plugin);
        }
        held.merge(key, 1, Integer::sum);
    }

    /** Lets go of one hold of the chunk with {@code key}; the last one removes the ticket. */
    void release(final World world, final long key) {
        final Map<Long, Integer> held = holds.getOrDefault(world.getName(), Map.of());
        final @Nullable Integer count = held.get(key);
        if (count == null) {
            return;
        }
        if (count > 1) {
            held.put(key, count - 1);
            return;
        }
        held.remove(key);
        world.removePluginChunkTicket((int) key, (int) (key >> 32), plugin);
    }
}
