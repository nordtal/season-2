package eu.nordtal.season.smp.rail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rail;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Reads the rails of a world on the main thread, for one walk.
 * A chunk that is not loaded is loaded off the main thread and held until {@link #release()}.
 * A chunk that fails to load {@link #TRIES} times, {@link #SPACING} loads apart, is given up on: the track ends there.
 */
final class BukkitRails implements RailView {

    /** How often a chunk may fail to load before the walk gives up on it. */
    static final int TRIES = 5;

    /** How many calls of {@link #load} pass between two tries of a chunk that failed, a second at one per tick. */
    static final int SPACING = 20;

    private final Plugin plugin;
    private final ChunkTickets tickets;
    private final World world;
    private final Set<Long> requested = new HashSet<>();
    private final Set<Long> held = new HashSet<>();
    private final Set<Long> ungenerated = new HashSet<>();
    private final List<CompletableFuture<?>> loads = new ArrayList<>();
    private final Set<RailPos> failed = new HashSet<>();
    private final Map<Long, Integer> notBefore = new HashMap<>();
    private final Map<Long, Integer> failures = new HashMap<>();
    private final Set<Long> givenUp = new HashSet<>();
    private int loadCalls;
    private boolean warned;
    private boolean released;

    BukkitRails(final Plugin plugin, final ChunkTickets tickets, final World world) {
        this.plugin = plugin;
        this.tickets = tickets;
        this.world = world;
    }

    @Override
    public boolean readable(final RailPos pos) {
        return outsideHeight(pos)
                || ungenerated.contains(Chunk.getChunkKey(pos.x() >> 4, pos.z() >> 4))
                || world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4);
    }

    @Override
    public boolean givenUp(final RailPos pos) {
        return givenUp.contains(Chunk.getChunkKey(pos.x() >> 4, pos.z() >> 4));
    }

    /** Returns the keys of the chunks this walk gave up on. */
    Set<Long> givenUpChunks() {
        return Set.copyOf(givenUp);
    }

    /** Whether the chunk with {@code key}, as {@link Chunk#getChunkKey()} gives it, is loaded now. */
    boolean loaded(final long key) {
        return world.isChunkLoaded((int) key, (int) (key >> 32));
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
        loads.removeIf(CompletableFuture::isDone);
        loadCalls++;
        final Set<RailPos> again = new HashSet<>(positions);
        again.addAll(failed);
        failed.clear();
        for (final RailPos pos : again) {
            final int chunkX = pos.x() >> 4;
            final int chunkZ = pos.z() >> 4;
            final long key = Chunk.getChunkKey(chunkX, chunkZ);
            if (givenUp.contains(key) || requested.contains(key)) {
                continue;
            }
            if (notBefore.getOrDefault(key, 0) > loadCalls) {
                failed.add(pos);
                continue;
            }
            requested.add(key);
            final CompletableFuture<Chunk> loading = world.getChunkAtAsync(chunkX, chunkZ, false);
            loads.add(loading.handle((chunk, failure) -> {
                if (failure == null) {
                    held(chunkX, chunkZ, chunk);
                } else {
                    failedToLoad(pos, failure);
                }
                return null;
            }));
        }
    }

    /** Holds a chunk that finished loading, unless the walk was released while it loaded. */
    private void held(final int chunkX, final int chunkZ, final @Nullable Chunk chunk) {
        if (released) {
            return;
        }
        if (chunk == null) {
            ungenerated.add(Chunk.getChunkKey(chunkX, chunkZ));
        } else if (held.add(chunk.getChunkKey())) {
            tickets.hold(world, chunkX, chunkZ);
        }
    }

    /** Asks for a failed chunk again, so its rails are never taken for missing, until its {@link #TRIES} are used. */
    private void failedToLoad(final RailPos pos, final Throwable failure) {
        if (released) {
            return;
        }
        final long key = Chunk.getChunkKey(pos.x() >> 4, pos.z() >> 4);
        if (!requested.remove(key)) {
            return;
        }
        if (failures.merge(key, 1, Integer::sum) >= TRIES) {
            givenUp.add(key);
            plugin.getLogger()
                    .log(
                            Level.WARNING,
                            "chunk " + (pos.x() >> 4) + "," + (pos.z() >> 4) + " of " + world.getName()
                                    + " failed to load " + TRIES + " times; its rail network is walked as if the track"
                                    + " ended there, and again once the chunk loads",
                            failure);
            return;
        }
        if (!warned) {
            warned = true;
            plugin.getLogger().log(Level.WARNING, "a chunk of a rail network failed to load, asking again", failure);
        }
        notBefore.put(key, loadCalls + SPACING);
        failed.add(pos);
    }

    /** Lets go of the chunks this walk held loaded. */
    void release() {
        released = true;
        loads.forEach(load -> load.cancel(false));
        loads.clear();
        for (final long key : held) {
            tickets.release(world, key);
        }
        held.clear();
    }
}
