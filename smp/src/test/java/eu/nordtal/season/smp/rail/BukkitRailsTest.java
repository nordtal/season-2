package eu.nordtal.season.smp.rail;

import static eu.nordtal.season.smp.rail.Track.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
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
    private final World world = world();
    private final Plugin plugin = plugin();

    /** Walks as the networks do, a tick at a time, and returns the walk once it ended. */
    private RailWalk walked(final BukkitRails view, final RailPos... starts) {
        final RailWalk walk = new RailWalk(view, List.of(starts), 1_000);
        for (int tick = 0; tick < 1_000; tick++) {
            view.load(walk.takeWanted());
            if (walk.step(2_000)) {
                return walk;
            }
        }
        throw new AssertionError("the walk did not end");
    }

    @Test
    void aChunkThatNeverLoadsEndsTheTrackThere() {
        track.eastWest(0, 40, 64, 0);
        failuresLeft.put(BAD, Integer.MAX_VALUE);
        final BukkitRails view = new BukkitRails(plugin, world);

        final RailWalk walk = walked(view, at(0, 64, 0));

        assertEquals(16, walk.found().getFirst().rails().size(), "the track ends where the chunk begins");
        assertEquals(Set.of(BAD), view.givenUpChunks());
    }

    @Test
    void aChunkThatNeverLoadsStopsNoOtherNetwork() {
        track.eastWest(0, 40, 64, 0).eastWest(0, 9, 64, 100);
        failuresLeft.put(BAD, Integer.MAX_VALUE);

        final RailWalk walk = walked(new BukkitRails(plugin, world), at(0, 64, 0), at(0, 64, 100));

        assertEquals(16, walk.found().get(0).rails().size());
        assertEquals(10, walk.found().get(1).rails().size());
    }

    @Test
    void aChunkThatFailedIsAskedForAgainOnlyAfterTheSpacing() {
        track.eastWest(0, 40, 64, 0);
        failuresLeft.put(BAD, Integer.MAX_VALUE);
        final BukkitRails view = new BukkitRails(plugin, world);
        final RailWalk walk = new RailWalk(view, List.of(at(0, 64, 0)), 1_000);

        for (int tick = 0; tick < BukkitRails.SPACING; tick++) {
            view.load(walk.takeWanted());
            walk.step(2_000);
        }

        assertEquals(1, asked.getOrDefault(BAD, 0), "the chunk is asked for once in its first spacing");
    }

    @Test
    void aStationInAChunkThatNeverLoadsIsNotTakenForMissing() {
        track.eastWest(0, 40, 64, 0);
        failuresLeft.put(BAD, Integer.MAX_VALUE);

        final RailWalk walk = walked(new BukkitRails(plugin, world), at(20, 64, 0));

        assertTrue(walk.found().isEmpty());
        assertTrue(walk.missing().isEmpty(), "a station whose chunk could not be read is kept");
    }

    @Test
    void aChunkThatFailsFewerTimesThanItsTriesIsWalked() {
        track.eastWest(0, 40, 64, 0);
        failuresLeft.put(BAD, BukkitRails.TRIES - 1);
        final BukkitRails view = new BukkitRails(plugin, world);

        final RailWalk walk = walked(view, at(0, 64, 0));

        assertEquals(41, walk.found().getFirst().rails().size());
        assertTrue(view.givenUpChunks().isEmpty());
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
                    case "removePluginChunkTicket" -> true;
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
        loaded.add(key);
        return CompletableFuture.completedFuture((Chunk) Proxy.newProxyInstance(
                Chunk.class.getClassLoader(),
                new Class<?>[] {Chunk.class},
                (_, method, _) -> switch (method.getName()) {
                    case "addPluginChunkTicket" -> true;
                    case "getChunkKey" -> key;
                    default -> throw new UnsupportedOperationException(method.getName());
                }));
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
