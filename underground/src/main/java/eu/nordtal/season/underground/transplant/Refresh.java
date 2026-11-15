package eu.nordtal.season.underground.transplant;

import eu.nordtal.season.underground.anvil.Nbt;
import eu.nordtal.season.underground.anvil.Region;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Brings a map upgraded from an earlier copy up to a newer copy of the same world, without a second upgrade.
 * A chunk the server saved since keeps its new state, every other one its upgraded state.
 */
public final class Refresh {

    private static final String[] KINDS = {"region", "entities"};

    /** How far, in regions around a changed chunk, a result can change: its own tile, and entrances beside it. */
    static final int AFFECTED = 2;

    private Refresh() {}

    /**
     * The smallest and largest chunk x and z of the chunks that changed.
     *
     * @param minX the smallest chunk x
     * @param minZ the smallest chunk z
     * @param maxX the largest chunk x
     * @param maxZ the largest chunk z
     */
    public record Bounds(int minX, int minZ, int maxX, int maxZ) {

        Bounds widened(final int chunkX, final int chunkZ) {
            return new Bounds(
                    Math.min(minX, chunkX), Math.min(minZ, chunkZ), Math.max(maxX, chunkX), Math.max(maxZ, chunkZ));
        }

        /** Returns the regions whose result can differ, as x1,z1,x2,z2. */
        public int[] affected() {
            return grown(AFFECTED);
        }

        /** Returns the regions a transplant must run over so the affected ones get every entrance beside them. */
        public int[] run() {
            return grown(AFFECTED + 1);
        }

        private int[] grown(final int regions) {
            return new int[] {
                Math.floorDiv(minX, Region.SIDE) - regions,
                Math.floorDiv(minZ, Region.SIDE) - regions,
                Math.floorDiv(maxX, Region.SIDE) + regions,
                Math.floorDiv(maxZ, Region.SIDE) + regions
            };
        }
    }

    /**
     * What changed between the two copies.
     *
     * @param chunks the number of chunks the server saved with other contents since the earlier copy
     * @param bounds where those chunks lie, or null when none changed
     */
    public record Changes(int chunks, @Nullable Bounds bounds) {}

    /** Writes the refreshed map into out and returns what changed; a changed chunk must have the upgraded version. */
    public static Changes run(final Path before, final Path now, final Path upgraded, final Path out)
            throws IOException {
        final int version = version(upgraded);
        int chunks = 0;
        Bounds bounds = null;
        for (final String kind : KINDS) {
            Files.createDirectories(out.resolve(kind));
            for (final Path file : files(upgraded.resolve(kind), "*.mc[ac]")) {
                final Path copy = out.resolve(kind).resolve(file.getFileName());
                Files.deleteIfExists(copy);
                if (file.getFileName().toString().endsWith(".mca")) {
                    // A link costs no space; a file with changes is written anew, never through it.
                    Files.createLink(copy, file);
                } else {
                    Files.copy(file, copy);
                }
            }
            for (final Path file : files(now.resolve(kind), "r.*.mca")) {
                final Path name = file.getFileName();
                final int[] region = Region.coordinates(file);
                if (region == null) {
                    continue;
                }
                final Map<Integer, byte[]> stored = Region.stored(file);
                final Set<Integer> changed = changed(before.resolve(kind).resolve(name), file, stored);
                final Path target = out.resolve(kind).resolve(name);
                if (changed.isEmpty() && Region.stored(target).keySet().equals(stored.keySet())) {
                    continue;
                }
                final Map<Integer, Map<String, Object>> result = Region.read(target);
                result.keySet().retainAll(stored.keySet());
                for (final int index : stored.keySet()) {
                    if (!changed.contains(index) && !result.containsKey(index)) {
                        throw new IOException("The upgraded map lacks chunk " + index + " of " + file
                                + ", which did not change since the earlier copy; name the map upgraded from it.");
                    }
                }
                final Map<Integer, Map<String, Object>> current = Region.read(file, changed::contains);
                for (final int index : changed) {
                    final Map<String, Object> chunk = Objects.requireNonNull(current.get(index));
                    if (Nbt.number(chunk, "DataVersion", 0) != version) {
                        throw new IOException("Chunk " + index + " of " + file + " changed since the earlier copy but"
                                + " has the data version " + Nbt.number(chunk, "DataVersion", 0) + ", not " + version
                                + " like the upgraded map; upgrade the newer copy instead.");
                    }
                    result.put(index, chunk);
                    chunks++;
                    final int chunkX = region[0] * Region.SIDE + index % Region.SIDE;
                    final int chunkZ = region[1] * Region.SIDE + index / Region.SIDE;
                    bounds = bounds == null
                            ? new Bounds(chunkX, chunkZ, chunkX, chunkZ)
                            : bounds.widened(chunkX, chunkZ);
                }
                Files.deleteIfExists(target);
                Region.write(target, result);
            }
        }
        return new Changes(chunks, bounds);
    }

    /** Returns the chunks of the newer file that are new or hold other contents than in the earlier one. */
    static Set<Integer> changed(final Path before, final Path now, final Map<Integer, byte[]> stored)
            throws IOException {
        final Map<Integer, byte[]> earlier = Region.stored(before);
        final Set<Integer> differ = new TreeSet<>();
        stored.forEach((index, bytes) -> {
            if (!Arrays.equals(bytes, earlier.get(index))) {
                differ.add(index);
            }
        });
        final Map<Integer, Map<String, Object>> old = Region.read(before, differ::contains);
        final Map<Integer, Map<String, Object>> fresh = Region.read(now, differ::contains);
        final Set<Integer> changed = new TreeSet<>();
        for (final int index : differ) {
            final Map<String, Object> was = old.get(index);
            if (was == null || !Arrays.equals(Nbt.write(was), Nbt.write(Objects.requireNonNull(fresh.get(index))))) {
                changed.add(index);
            }
        }
        return changed;
    }

    /** Returns the data version of the upgraded map, which every one of its chunks has. */
    private static int version(final Path upgraded) throws IOException {
        for (final Path file : files(upgraded.resolve("region"), "r.*.mca")) {
            for (final Map<String, Object> chunk : Region.read(file).values()) {
                return Nbt.number(chunk, "DataVersion", 0);
            }
        }
        throw new IOException(upgraded + " holds no chunk; name the upgraded map.");
    }

    private static List<Path> files(final Path directory, final String pattern) throws IOException {
        final List<Path> files = new ArrayList<>();
        if (Files.isDirectory(directory)) {
            try (DirectoryStream<Path> found = Files.newDirectoryStream(directory, pattern)) {
                found.forEach(files::add);
            }
        }
        files.sort(null);
        return files;
    }
}
