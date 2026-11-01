package eu.nordtal.season.smp.rail;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rail;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Reads the rails of a world on the main thread, for one walk.
 * A chunk that is not loaded is loaded off the main thread and held until {@link #release()}.
 */
final class BukkitRails implements RailView {

    private final Plugin plugin;
    private final World world;
    private final Set<Long> requested = new HashSet<>();
    private final Set<Long> held = new HashSet<>();
    private final Set<Long> ungenerated = new HashSet<>();
    private final List<CompletableFuture<Void>> loads = new ArrayList<>();
    private boolean released;

    BukkitRails(final Plugin plugin, final World world) {
        this.plugin = plugin;
        this.world = world;
    }

    @Override
    public boolean readable(final RailPos pos) {
        return outsideHeight(pos)
                || ungenerated.contains(Chunk.getChunkKey(pos.x() >> 4, pos.z() >> 4))
                || world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4);
    }

    @Override
    public @Nullable RailShape shape(final RailPos pos) {
        if (outsideHeight(pos) || ungenerated.contains(Chunk.getChunkKey(pos.x() >> 4, pos.z() >> 4))) {
            return null;
        }
        final BlockData data = world.getBlockData(pos.x(), pos.y(), pos.z());
        return data instanceof Rail rail ? RailShape.valueOf(rail.getShape().name()) : null;
    }

    private boolean outsideHeight(final RailPos pos) {
        return pos.y() < world.getMinHeight() || pos.y() >= world.getMaxHeight();
    }

    /** Loads the chunks of {@code positions} off the main thread, each once, without generating any. */
    void load(final Set<RailPos> positions) {
        for (final RailPos pos : positions) {
            final int chunkX = pos.x() >> 4;
            final int chunkZ = pos.z() >> 4;
            if (!requested.add(Chunk.getChunkKey(chunkX, chunkZ))) {
                continue;
            }
            final CompletableFuture<Chunk> loading = world.getChunkAtAsync(chunkX, chunkZ, false);
            loads.add(loading.thenAccept(chunk -> held(chunkX, chunkZ, chunk)));
        }
    }

    /** Holds a chunk that finished loading, unless the walk was released while it loaded. */
    private void held(final int chunkX, final int chunkZ, final @Nullable Chunk chunk) {
        if (released) {
            return;
        }
        if (chunk == null) {
            ungenerated.add(Chunk.getChunkKey(chunkX, chunkZ));
        } else if (chunk.addPluginChunkTicket(plugin)) {
            held.add(chunk.getChunkKey());
        }
    }

    /** Lets go of the chunks this walk held loaded. */
    void release() {
        released = true;
        loads.forEach(load -> load.cancel(false));
        loads.clear();
        for (final long key : held) {
            world.removePluginChunkTicket((int) key, (int) (key >> 32), plugin);
        }
        held.clear();
    }
}
