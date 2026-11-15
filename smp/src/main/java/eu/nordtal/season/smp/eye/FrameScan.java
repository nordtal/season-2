package eu.nordtal.season.smp.eye;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/** Brings a world's entry in the index level with its region files, reading only the files that changed. */
final class FrameScan {

    /**
     * How a world's scan went: region files read, skipped as unchanged and not readable.
     * A stopped scan keeps what it read, but leaves the region files it did not reach as they were.
     */
    record Outcome(int read, int unchanged, int failed, boolean stopped) {}

    private FrameScan() {}

    /**
     * Scans the region directory of one world into the index; a missing directory holds no frames.
     * Ends early, before the next region file, once {@code stopped} is true.
     */
    static Outcome world(final FrameIndex index, final String world, final Path regions, final BooleanSupplier stopped)
            throws IOException {
        final Set<String> present = new HashSet<>();
        int read = 0;
        int unchanged = 0;
        int failed = 0;
        final RegionFrames reader = new RegionFrames();
        try {
            for (final Path file : files(regions)) {
                if (stopped.getAsBoolean()) {
                    return new Outcome(read, unchanged, failed, true);
                }
                final String name = file.getFileName().toString();
                present.add(name);
                final long size;
                final long modified;
                try {
                    size = Files.size(file);
                    modified = Files.getLastModifiedTime(file).toMillis();
                } catch (final IOException e) {
                    failed++;
                    continue;
                }
                final FrameIndex.Region cached = index.region(world, name);
                if (cached != null && cached.matches(size, modified)) {
                    unchanged++;
                    continue;
                }
                try {
                    final RegionFrames.Scanned scanned = reader.scan(file);
                    final long stored = scanned.complete() ? size : FrameIndex.Region.INCOMPLETE;
                    index.scanned(world, name, new FrameIndex.Region(stored, modified, scanned.frames()));
                    read++;
                    if (!scanned.complete()) {
                        failed++;
                    }
                } catch (final IOException e) {
                    failed++;
                }
            }
        } finally {
            reader.close();
        }
        if (stopped.getAsBoolean()) {
            return new Outcome(read, unchanged, failed, true);
        }
        index.finished(world, present);
        return new Outcome(read, unchanged, failed, false);
    }

    private static List<Path> files(final Path regions) throws IOException {
        if (!Files.isDirectory(regions)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(regions)) {
            return files.filter(file -> RegionFrames.coordinates(file) != null)
                    .sorted()
                    .toList();
        }
    }
}
