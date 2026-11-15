package eu.nordtal.season.underground.structures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** What a scan found: spawners, hives, beds, chests, portal frames, suspicious blocks, starts and the chunks. */
public final class Finds {

    /** A spawner block with the mobs its data names; an empty spawner names none. */
    public record Spawner(int x, int y, int z, boolean trial, List<String> mobs) {

        /** Returns whether the spawner names this mob, such as minecraft:pillager. */
        public boolean spawns(final String mob) {
            return mobs.contains(mob);
        }
    }

    /** A beehive or bee nest with the number of bees inside. */
    public record Hive(int x, int y, int z, int bees) {}

    /** A block position. */
    public record Point(int x, int y, int z) {}

    /** A chest with the loot table it rolls when first opened, null for none, and how many stacks it holds. */
    public record Chest(int x, int y, int z, @Nullable String lootTable, int stacks) {}

    /** A structure start already in the world, with the box around its pieces. */
    public record Start(String id, int chunkX, int chunkZ, Box box) {}

    final List<Spawner> spawners = new ArrayList<>();
    final List<Hive> hives = new ArrayList<>();
    final List<Point> beds = new ArrayList<>();
    final List<Point> chests = new ArrayList<>();
    final List<Chest> loot = new ArrayList<>();
    final List<Point> frames = new ArrayList<>();
    final List<Point> suspicious = new ArrayList<>();
    final List<Start> starts = new ArrayList<>();
    final Set<Long> fullChunks = new HashSet<>();
    final NavigableMap<Integer, Integer> versions = new TreeMap<>();
    final Map<Long, Integer> chunkVersions = new HashMap<>();

    /** Adds what another scan found. */
    synchronized void add(final Finds other) {
        spawners.addAll(other.spawners);
        hives.addAll(other.hives);
        beds.addAll(other.beds);
        chests.addAll(other.chests);
        loot.addAll(other.loot);
        frames.addAll(other.frames);
        suspicious.addAll(other.suspicious);
        starts.addAll(other.starts);
        fullChunks.addAll(other.fullChunks);
        other.versions.forEach((version, count) -> versions.merge(version, count, Integer::sum));
        chunkVersions.putAll(other.chunkVersions);
    }

    /** Returns whether a chunk is in the world and generated to the end. */
    public boolean full(final int chunkX, final int chunkZ) {
        return fullChunks.contains(key(chunkX, chunkZ));
    }

    /** Returns the data version of a chunk, or -1 when the world has no such chunk. */
    public int version(final int chunkX, final int chunkZ) {
        return chunkVersions.getOrDefault(key(chunkX, chunkZ), -1);
    }

    /** Returns the key of a chunk in the set of full chunks. */
    static long key(final int chunkX, final int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFF_FFFFL);
    }
}
