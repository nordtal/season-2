package eu.nordtal.season.smp.rail;

import static eu.nordtal.season.smp.rail.Track.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rail;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class BukkitRailsTest {

    /** The chunk at x 16 to 31, z 0 to 15. */
    private static final long BAD = Chunk.getChunkKey(1, 0);

    private final Track track = new Track();
    private final Set<Long> loaded = new HashSet<>();
    private final Map<Long, Integer> failuresLeft = new HashMap<>();
    private final Map<Long, Integer> asked = new HashMap<>();
    private final List<Runnable> pending = new ArrayList<>();
    private boolean deferred;
    private final World world = world();
    private final Plugin plugin = plugin();

    /** Traces from {@code stations} as the networks do, a tick at a time, and returns the trace once it ended. */
    private RailTrace traced(final BukkitRails view, final RailPos... stations) {
        final RailTrace trace = trace(view, stations);
        for (int tick = 0; tick < 1_000; tick++) {
            view.load(trace.takeWanted());
            if (trace.step(2_000, Long.MAX_VALUE)) {
                return trace;
            }
        }
        throw new AssertionError("the trace did not end");
    }

    private RailTrace trace(final BukkitRails view, final RailPos... stations) {
        final Set<RailPos> onTrack = Set.of(stations);
        final RailTrace trace = new RailTrace(view, new RailTrace.Known() {
            @Override
            public @Nullable RailShape station(final RailPos pos) {
                return onTrack.contains(pos) ? track.rails().getOrDefault(pos, RailShape.EAST_WEST) : null;
            }

            @Override
            public boolean settled(final RailPos pos) {
                return false;
            }

            @Override
            public @Nullable RailLeg same(final RailLeg leg) {
                return null;
            }
        });
        onTrack.forEach(trace::look);
        return trace;
    }

    private static int railsFrom(final RailTrace trace, final RailPos station) {
        return trace.traced().stream()
                .filter(leg -> leg.from().equals(station))
                .mapToInt(leg -> leg.rails().size())
                .sum();
    }

    @Test
    void aChunkThatNeverLoadsEndsTheTrackThere() {
        track.eastWest(0, 40, 64, 0);
        failuresLeft.put(BAD, Integer.MAX_VALUE);
        final BukkitRails view = new BukkitRails(plugin, new ChunkTickets(plugin), world);

        final RailTrace trace = traced(view, at(0, 64, 0));

        assertEquals(15, railsFrom(trace, at(0, 64, 0)), "the track ends where the chunk begins");
        assertEquals(Set.of(BAD), view.givenUpChunks());
        assertEquals(Set.of(BAD), trace.givenUp().keySet());
    }

    @Test
    void aChunkThatNeverLoadsStopsNoOtherNetwork() {
        track.eastWest(0, 40, 64, 0).eastWest(0, 9, 64, 100);
        failuresLeft.put(BAD, Integer.MAX_VALUE);

        final RailTrace trace =
                traced(new BukkitRails(plugin, new ChunkTickets(plugin), world), at(0, 64, 0), at(0, 64, 100));

        assertEquals(15, railsFrom(trace, at(0, 64, 0)));
        assertEquals(9, railsFrom(trace, at(0, 64, 100)));
    }

    @Test
    void aChunkThatFailedIsAskedForAgainOnlyAfterTheSpacing() {
        track.eastWest(0, 40, 64, 0);
        failuresLeft.put(BAD, Integer.MAX_VALUE);
        final BukkitRails view = new BukkitRails(plugin, new ChunkTickets(plugin), world);
        final RailTrace trace = trace(view, at(0, 64, 0));

        for (int tick = 0; tick < BukkitRails.SPACING; tick++) {
            view.load(trace.takeWanted());
            trace.step(2_000, Long.MAX_VALUE);
        }

        assertEquals(1, asked.getOrDefault(BAD, 0), "the chunk is asked for once in its first spacing");
    }

    @Test
    void aStationInAChunkThatNeverLoadsIsNotTakenForMissing() {
        track.eastWest(0, 40, 64, 0);
        failuresLeft.put(BAD, Integer.MAX_VALUE);

        final RailTrace trace = traced(new BukkitRails(plugin, new ChunkTickets(plugin), world), at(20, 64, 0));

        assertTrue(trace.traced().isEmpty());
        assertTrue(trace.missing().isEmpty(), "a station whose chunk could not be read is kept");
        assertEquals(Set.of(at(20, 64, 0)), trace.unread());
    }

    @Test
    void aChunkThatFailsFewerTimesThanItsTriesIsRead() {
        track.eastWest(0, 40, 64, 0);
        failuresLeft.put(BAD, BukkitRails.TRIES - 1);
        final BukkitRails view = new BukkitRails(plugin, new ChunkTickets(plugin), world);

        final RailTrace trace = traced(view, at(0, 64, 0));

        assertEquals(40, railsFrom(trace, at(0, 64, 0)));
        assertTrue(view.givenUpChunks().isEmpty());
    }

    @Test
    void onlyAFewChunksLoadAtOnce() {
        deferred = true;
        final BukkitRails view = new BukkitRails(plugin, new ChunkTickets(plugin), world);
        final Set<RailPos> wanted = new HashSet<>();
        for (int chunk = 0; chunk < 3 * BukkitRails.LOADING; chunk++) {
            wanted.add(at(chunk * 16, 64, 0));
        }

        view.load(wanted);
        assertEquals(BukkitRails.LOADING, asked.size());

        view.load(Set.of());
        assertEquals(BukkitRails.LOADING, asked.size(), "the others wait while those load");

        pending.forEach(Runnable::run);
        pending.clear();
        view.load(Set.of());
        assertEquals(2 * BukkitRails.LOADING, asked.size(), "the next ones load once those are done");
    }

    @Test
    void aChunkTheReCheckNoLongerReadsIsLetGo() {
        final BukkitRails view = new BukkitRails(plugin, new ChunkTickets(plugin), world);
        view.load(Set.of(at(0, 64, 0), at(100, 64, 0)));
        view.load(Set.of());
        assertEquals(2, view.holding());

        for (int tick = 0; tick <= BukkitRails.IDLE; tick++) {
            view.load(Set.of());
            assertTrue(view.readable(at(0, 64, 0)));
        }

        assertEquals(1, view.holding(), "the chunk read every tick is held, the other let go");
    }

    private World world() {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[] {World.class},
                (_, method, args) -> switch (method.getName()) {
                    case "getName" -> "world";
                    case "getMinHeight" -> -64;
                    case "getMaxHeight" -> 320;
                    case "isChunkLoaded" -> loaded.contains(Chunk.getChunkKey((int) args[0], (int) args[1]));
                    case "getChunkAtAsync" -> load((int) args[0], (int) args[1]);
                    case "getBlockData" -> blockData(track.shape(at((int) args[0], (int) args[1], (int) args[2])));
                    case "addPluginChunkTicket", "removePluginChunkTicket" -> true;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private CompletableFuture<Chunk> load(final int chunkX, final int chunkZ) {
        final long key = Chunk.getChunkKey(chunkX, chunkZ);
        asked.merge(key, 1, Integer::sum);
        final int left = failuresLeft.getOrDefault(key, 0);
        if (left > 0) {
            failuresLeft.put(key, left - 1);
            return CompletableFuture.failedFuture(new IllegalStateException("the chunk is corrupt"));
        }
        final Chunk chunk = chunk(key);
        if (deferred) {
            final CompletableFuture<Chunk> loading = new CompletableFuture<>();
            pending.add(() -> {
                loaded.add(key);
                loading.complete(chunk);
            });
            return loading;
        }
        loaded.add(key);
        return CompletableFuture.completedFuture(chunk);
    }

    private static Chunk chunk(final long key) {
        return (Chunk) Proxy.newProxyInstance(
                Chunk.class.getClassLoader(),
                new Class<?>[] {Chunk.class},
                (_, method, _) -> switch (method.getName()) {
                    case "addPluginChunkTicket" -> true;
                    case "getChunkKey" -> key;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static BlockData blockData(final @Nullable RailShape shape) {
        if (shape == null) {
            return (BlockData) Proxy.newProxyInstance(
                    BlockData.class.getClassLoader(), new Class<?>[] {BlockData.class}, (_, method, _) -> {
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
        return (Rail) Proxy.newProxyInstance(
                Rail.class.getClassLoader(), new Class<?>[] {Rail.class}, (_, method, _) -> switch (method.getName()) {
                    case "getShape" -> Rail.Shape.valueOf(shape.name());
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Plugin plugin() {
        final Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[] {Plugin.class},
                (_, method, _) -> switch (method.getName()) {
                    case "getLogger" -> logger;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
