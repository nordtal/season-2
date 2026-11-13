package eu.nordtal.season.smp.rail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
 * Reads the rails of a world on the main thread, loading at most {@link #LOADING} chunks at a time off it.
 * A chunk not read for {@link #IDLE} calls of {@link #load} is let go, as are all at {@link #release()}; one that
 * fails to load {@link #TRIES} times, {@link #SPACING} loads apart, is given up on, and the track ends there.
 */
final class BukkitRails implements RailView {

    /** How often a chunk may fail to load before the walk gives up on it. */
    static final int TRIES = 5;

    /** How many calls of {@link #load} pass between two tries of a chunk that failed, a second at one per tick. */
    static final int SPACING = 20;

    /** How many chunks load at once; the others wait their turn. */
    static final int LOADING = 8;

    /** How many calls of {@link #load} a held chunk stays held without being read. */
    static final int IDLE = 40;

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
    private final Map<Long, Integer> lastRead = new HashMap<>();
    private final Map<Long, RailPos> backlog = new LinkedHashMap<>();
    private long lastKey = Long.MIN_VALUE;
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
        if (outsideHeight(pos)) {
            return true;
        }
        final long key = Chunk.getChunkKey(pos.x() >> 4, pos.z() >> 4);
        if (key == lastKey) {
            return true;
        }
        if (ungenerated.contains(key)) {
            return true;
        }
        if (world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
            read(key);
            return true;
        }
        return false;
    }

    /** Notes that the chunk with {@code key}, which is loaded, is read now, so it stays held a while longer. */
    private void read(final long key) {
        lastKey = key;
        if (held.contains(key)) {
            lastRead.put(key, loadCalls);
        }
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
        if (outsideHeight(pos)) {
            return null;
        }
        final long key = Chunk.getChunkKey(pos.x() >> 4, pos.z() >> 4);
        if (key != lastKey) {
            if (ungenerated.contains(key)) {
                return null;
            }
            read(key);
        }
        final BlockData data = world.getBlockData(pos.x(), pos.y(), pos.z());
        return data instanceof Rail rail ? RailShape.valueOf(rail.getShape().name()) : null;
    }

    private boolean outsideHeight(final RailPos pos) {
        return pos.y() < world.getMinHeight() || pos.y() >= world.getMaxHeight();
    }

    /**
     * Loads the chunks of {@code positions} off the main thread, each once, and lets go of those no longer read.
     * It generates no chunk, and is called once a tick.
     */
    void load(final Set<RailPos> positions) {
        loads.removeIf(CompletableFuture::isDone);
        loadCalls++;
        lastKey = Long.MIN_VALUE;
        letGoIdle();
        final Set<RailPos> again = new HashSet<>(positions);
        again.addAll(failed);
        failed.clear();
        for (final RailPos pos : again) {
            final long key = Chunk.getChunkKey(pos.x() >> 4, pos.z() >> 4);
            if (givenUp.contains(key) || requested.contains(key)) {
                continue;
            }
            if (notBefore.getOrDefault(key, 0) > loadCalls) {
                failed.add(pos);
                continue;
            }
            backlog.putIfAbsent(key, pos);
        }
        final Iterator<RailPos> next = backlog.values().iterator();
        while (loads.size() < LOADING && next.hasNext()) {
            final RailPos pos = next.next();
            next.remove();
            request(pos);
        }
    }

    private void request(final RailPos pos) {
        final int chunkX = pos.x() >> 4;
        final int chunkZ = pos.z() >> 4;
        final long key = Chunk.getChunkKey(chunkX, chunkZ);
        if (givenUp.contains(key) || requested.contains(key)) {
            return;
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

    /** Lets go of the held chunks the re-check did not read for {@link #IDLE} calls, so they may unload. */
    private void letGoIdle() {
        final Iterator<Long> keys = held.iterator();
        while (keys.hasNext()) {
            final long key = keys.next();
            if (loadCalls - lastRead.getOrDefault(key, loadCalls) > IDLE) {
                keys.remove();
                lastRead.remove(key);
                requested.remove(key);
                tickets.release(world, key);
            }
        }
    }

    /** Returns how many chunks this re-check holds loaded now. */
    int holding() {
        return held.size();
    }

    /** Holds a chunk that finished loading, unless the walk was released while it loaded. */
    private void held(final int chunkX, final int chunkZ, final @Nullable Chunk chunk) {
        if (released) {
            return;
        }
        if (chunk == null) {
            ungenerated.add(Chunk.getChunkKey(chunkX, chunkZ));
        } else if (held.add(chunk.getChunkKey())) {
            lastRead.put(chunk.getChunkKey(), loadCalls);
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
                            "rail chunk not loaded, track treated as ended: world=" + world.getName() + " chunk_x="
                                    + (pos.x() >> 4) + " chunk_z=" + (pos.z() >> 4) + " attempt=" + TRIES,
                            failure);
            return;
        }
        if (!warned) {
            warned = true;
            plugin.getLogger().log(Level.WARNING, "rail chunk not loaded, load retried", failure);
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
        lastRead.clear();
        backlog.clear();
    }
}
