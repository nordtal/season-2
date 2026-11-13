package eu.nordtal.season.underground.server;

import eu.nordtal.season.underground.anvil.Nbt;
import eu.nordtal.season.underground.anvil.Region;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Brings every chunk of a copy of the map to the newest data version found in it, with the game's own upgrade.
 * The game skips old chunks in a region file that also holds current ones, so the old ones are upgraded apart.
 */
public final class Upgrade {

    private static final String[] KINDS = {"region", "entities"};
    private static final int ROUNDS = 3;

    private Upgrade() {}

    /** Writes the upgraded regions and entities of a world into another folder; points of interest are left out. */
    public static void run(final ScratchServer.Setup setup, final Path map, final Path out)
            throws IOException, InterruptedException {
        final int newest = newest(map);
        final Map<Path, Pending> old = new LinkedHashMap<>();
        for (final String kind : KINDS) {
            Files.createDirectories(out.resolve(kind));
            Files.createDirectories(setup.overworld().resolve(kind));
            for (final Path file : files(map.resolve(kind))) {
                final Map<Integer, Map<String, Object>> chunks = Region.read(file);
                final Map<Integer, Map<String, Object>> behind = new TreeMap<>();
                chunks.forEach((index, chunk) -> {
                    if (Nbt.number(chunk, "DataVersion", 0) != newest) {
                        behind.put(index, chunk);
                    }
                });
                behind.keySet().forEach(chunks::remove);
                Region.write(out.resolve(kind).resolve(file.getFileName()), chunks);
                if (!behind.isEmpty()) {
                    final Path scratch = setup.overworld().resolve(kind).resolve(file.getFileName());
                    Region.write(scratch, behind);
                    old.put(scratch, new Pending(out.resolve(kind).resolve(file.getFileName()), behind.keySet()));
                }
            }
        }
        System.out.println("region files found: newest_version=" + newest + " older=" + old.size());
        for (int round = 0; round < ROUNDS && !old.isEmpty(); round++) {
            ScratchServer.prepare(setup);
            ScratchServer.start(setup, "--forceUpgrade").close();
            old.entrySet().removeIf(file -> merged(file.getKey(), file.getValue(), newest));
        }
        if (!old.isEmpty()) {
            throw new IOException("The game did not upgrade every chunk sent to it in " + old.keySet());
        }
    }

    /**
     * The result file that the chunks of a scratch file go back into, and the chunks still waiting for the game.
     *
     * @param target the region file of the result
     * @param indices the indices of the chunks sent to the game and not upgraded yet
     */
    record Pending(Path target, Set<Integer> indices) {
        Pending {
            indices = new TreeSet<>(indices);
        }
    }

    /**
     * Moves the chunks the game has upgraded into the result and leaves the others for the next round.
     * A chunk the game made itself, such as one near its spawn, is dropped: it would replace a chunk of the map.
     */
    static boolean merged(final Path scratch, final Pending pending, final int newest) {
        try {
            final Map<Integer, Map<String, Object>> done = Region.read(pending.target());
            final Map<Integer, Map<String, Object>> left = new TreeMap<>();
            Region.read(scratch).forEach((index, chunk) -> {
                if (!pending.indices().contains(index)) {
                    return;
                }
                if (Nbt.number(chunk, "DataVersion", 0) == newest) {
                    done.put(index, chunk);
                    pending.indices().remove(index);
                } else {
                    left.put(index, chunk);
                }
            });
            Region.write(pending.target(), done);
            Region.write(scratch, left);
            return pending.indices().isEmpty();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int newest(final Path map) throws IOException {
        int newest = 0;
        for (final Path file : files(map.resolve("region"))) {
            for (final Map<String, Object> chunk : Region.read(file).values()) {
                newest = Math.max(newest, Nbt.number(chunk, "DataVersion", 0));
            }
        }
        return newest;
    }

    private static List<Path> files(final Path directory) throws IOException {
        final List<Path> files = new ArrayList<>();
        if (Files.isDirectory(directory)) {
            try (DirectoryStream<Path> found = Files.newDirectoryStream(directory, "r.*.mca")) {
                found.forEach(files::add);
            }
        }
        files.sort(null);
        return files;
    }
}
