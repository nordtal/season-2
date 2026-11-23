package eu.nordtal.season.stewardagent.pack;

import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.settings.PackState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the built packs as {@code <sha1>.zip} in the folder Caddy serves.
 *
 * Older packs stay a while, so a player who was offered one can still download it.
 */
final class PackShelf {

    private static final Logger log = LoggerFactory.getLogger(PackShelf.class);

    /** The name of a pack on the shelf, and the only path Caddy answers. */
    static final Pattern ZIP = Pattern.compile("^[0-9a-f]{40}\\.zip$");

    /** The name start of a build's folder. */
    private static final String UNFINISHED = ".build-";

    private final Path dir;
    private final int keep;
    private final Clock clock;

    /**
     * @param keep how many of the newest packs stay besides the active ones
     * @param clock dates a pack put on the shelf, which decides the newest
     */
    PackShelf(final Path dir, final int keep, final Clock clock) {
        if (keep < 1) {
            throw new IllegalArgumentException("keep must be at least 1, was " + keep);
        }
        this.dir = Objects.requireNonNull(dir, "dir");
        this.keep = keep;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Path dir() {
        return dir;
    }

    /** Returns the address Caddy serves the pack {@code sha1} under, below {@code baseUrl}. */
    static String url(final String baseUrl, final String sha1) {
        return baseUrl + "/" + sha1 + ".zip";
    }

    /** Returns whether the pack {@code sha1} is on the shelf. */
    boolean has(final String sha1) {
        return Files.isRegularFile(path(sha1));
    }

    /**
     * Moves the built zip {@code built} onto the shelf as {@code <sha1>.zip}, readable for Caddy.
     * A pack already there stays and counts as new again.
     *
     * @param built a file on the file system of {@link #dir()}, so the move is atomic
     */
    void put(final Path built, final String sha1) throws IOException {
        final Path target = path(sha1);
        if (Files.isRegularFile(target)) {
            Files.delete(built);
        } else {
            Files.setPosixFilePermissions(built, PosixFilePermissions.fromString("rw-r--r--"));
            Files.move(built, target, StandardCopyOption.ATOMIC_MOVE);
        }
        Files.setLastModifiedTime(target, FileTime.from(clock.instant()));
    }

    /** Deletes the old packs, keeping {@code built} and both packs the proxy offers, and logs what went. */
    void prune(final SettingStore settings, final String built) {
        try {
            final Set<String> active = new HashSet<>();
            active.add(built);
            Stream.of(PackState.read(settings), PackState.readMap(settings))
                    .map(PackState::sha1)
                    .filter(Objects::nonNull)
                    .forEach(active::add);
            final List<String> gone = prune(active);
            if (!gone.isEmpty()) {
                log.info("old packs removed: files={}", gone);
            }
        } catch (final IOException | RuntimeException failure) {
            log.warn("old packs not removed: cause={}", failure.getMessage());
        }
    }

    /**
     * Deletes every pack but the newest {@code keep} and the {@code active} ones.
     *
     * @return the names deleted
     */
    List<String> prune(final Set<String> active) throws IOException {
        final List<String> deleted = new ArrayList<>();
        final List<Path> packs;
        try (Stream<Path> files = Files.list(dir)) {
            packs = files.filter(
                            file -> ZIP.matcher(file.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(PackShelf::modified).reversed())
                    .toList();
        }
        for (final Path old : packs.subList(Math.min(keep, packs.size()), packs.size())) {
            final String name = old.getFileName().toString();
            if (!active.contains(name.substring(0, name.length() - ".zip".length())) && Files.deleteIfExists(old)) {
                deleted.add(name);
            }
        }
        return deleted;
    }

    /** Returns a fresh folder for one build, on the shelf's file system and never served. */
    Path workspace() throws IOException {
        return Files.createTempDirectory(dir, UNFINISHED);
    }

    /** Deletes the folders of builds that never finished, because the agent stopped during them. */
    void clearUnfinished() {
        try (Stream<Path> files = Files.list(dir)) {
            for (final Path left : files.filter(
                            file -> file.getFileName().toString().startsWith(UNFINISHED))
                    .toList()) {
                delete(left);
            }
        } catch (final IOException | UncheckedIOException failure) {
            // The next build makes a folder of its own, so a leftover only takes space.
        }
    }

    /** Deletes a build's {@code folder}, and logs instead of throwing when that fails. */
    static void deleteQuietly(final Path folder) {
        try {
            delete(folder);
        } catch (final IOException failure) {
            log.warn("build folder not removed: path={} cause={}", folder, failure.getMessage());
        }
    }

    /** Deletes {@code folder} and everything in it. */
    static void delete(final Path folder) throws IOException {
        try (Stream<Path> files = Files.walk(folder)) {
            for (final Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static FileTime modified(final Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private Path path(final String sha1) {
        final String name = sha1 + ".zip";
        if (!ZIP.matcher(name).matches()) {
            throw new IllegalArgumentException("not a SHA-1: " + sha1);
        }
        return dir.resolve(name);
    }
}
