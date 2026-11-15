package eu.nordtal.season.smp.eye;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The end portal frames of every world, from the scans of its region files and the frames placed or broken since.
 * The scans are cached in a file, so a start reads again only the region files that changed. Thread safe.
 */
final class FrameIndex {

    /** One region file as it was scanned: its size and modification time, and the frames it held. */
    record Region(long size, long modified, List<Frame> frames) {

        /** A size no file has, for a scan that missed a chunk and must run again. */
        static final long INCOMPLETE = -1;

        boolean matches(final long fileSize, final long fileModified) {
            return size != INCOMPLETE && size == fileSize && modified == fileModified;
        }
    }

    private static final class World {
        final Map<String, Region> regions = new TreeMap<>();
        final Set<Frame> placed = new HashSet<>();
        final Set<Frame> broken = new HashSet<>();

        @Nullable
        List<Portal> portals;
    }

    private final Map<String, World> worlds = new HashMap<>();

    /** Returns the portals of the world, grouped from every frame known there. */
    synchronized List<Portal> portals(final String world) {
        final World state = world(world);
        List<Portal> portals = state.portals;
        if (portals == null) {
            final Set<Frame> frames = new LinkedHashSet<>();
            state.regions.values().forEach(region -> frames.addAll(region.frames()));
            frames.removeAll(state.broken);
            frames.addAll(state.placed);
            portals = Portal.of(frames);
            state.portals = portals;
        }
        return portals;
    }

    synchronized void placed(final String world, final Frame frame) {
        final World state = world(world);
        state.broken.remove(frame);
        state.placed.add(frame);
        state.portals = null;
    }

    synchronized void broken(final String world, final Frame frame) {
        final World state = world(world);
        state.placed.remove(frame);
        state.broken.add(frame);
        state.portals = null;
    }

    synchronized @Nullable Region region(final String world, final String file) {
        return world(world).regions.get(file);
    }

    synchronized void scanned(final String world, final String file, final Region region) {
        final World state = world(world);
        state.regions.put(file, region);
        state.portals = null;
    }

    /** Ends a world's scan: forgets the region files that are gone. */
    synchronized void finished(final String world, final Set<String> files) {
        final World state = world(world);
        state.regions.keySet().retainAll(files);
        state.portals = null;
    }

    /** Reads the cache file; a line it cannot read is left out, so its region file is scanned again. */
    synchronized void load(final Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            final String[] parts = line.split("\t", -1);
            if (parts.length != 5) {
                continue;
            }
            final Region region;
            try {
                region = new Region(Long.parseLong(parts[2]), Long.parseLong(parts[3]), frames(parts[4]));
            } catch (final IllegalArgumentException e) {
                continue;
            }
            final World state = world(parts[0]);
            state.regions.put(parts[1], region);
            state.portals = null;
        }
    }

    private static List<Frame> frames(final String text) {
        final List<Frame> frames = new ArrayList<>();
        for (final String frame : text.isEmpty() ? new String[0] : text.split(" ", -1)) {
            final String[] xyz = frame.split(",", -1);
            if (xyz.length != 3) {
                throw new IllegalArgumentException("A frame has three coordinates: " + frame);
            }
            frames.add(new Frame(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])));
        }
        return List.copyOf(frames);
    }

    /** Writes the cache file in one move, so a crash leaves the old one whole. */
    synchronized void save(final Path file) throws IOException {
        final StringBuilder text = new StringBuilder();
        new TreeMap<>(worlds)
                .forEach((name, state) -> state.regions.forEach((region, scan) -> {
                    text.append(name).append('\t').append(region).append('\t');
                    text.append(scan.size())
                            .append('\t')
                            .append(scan.modified())
                            .append('\t');
                    final List<String> frames = scan.frames().stream()
                            .map(frame -> frame.x() + "," + frame.y() + "," + frame.z())
                            .toList();
                    text.append(String.join(" ", frames)).append('\n');
                }));
        Files.createDirectories(file.toAbsolutePath().getParent());
        final Path written = file.resolveSibling(file.getFileName() + ".new");
        Files.writeString(written, text, StandardCharsets.UTF_8);
        Files.move(written, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private World world(final String world) {
        return worlds.computeIfAbsent(world, name -> new World());
    }
}
