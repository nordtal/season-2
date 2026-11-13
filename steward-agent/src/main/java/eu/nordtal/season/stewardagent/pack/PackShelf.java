package eu.nordtal.season.stewardagent.pack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Holds the built packs as {@code <sha1>.zip} in the folder Caddy serves.
 *
 * Older packs stay a while, so a player who was offered one can still download it.
 */
final class PackShelf {

    /** The name of a pack on the shelf, and the only path Caddy answers. */
    static final Pattern ZIP = Pattern.compile("^[0-9a-f]{40}\\.zip$");

    /** The name start of a build's folder. */
    private static final String UNFINISHED = ".build-";

    private final Path dir;
    private final int keep;

    /** @param keep how many of the newest packs stay besides the active one */
    PackShelf(final Path dir, final int keep) {
        if (keep < 1) {
            throw new IllegalArgumentException("keep must be at least 1, was " + keep);
        }
        this.dir = Objects.requireNonNull(dir, "dir");
        this.keep = keep;
    }

    Path dir() {
        return dir;
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
        Files.setLastModifiedTime(target, FileTime.from(Instant.now()));
    }

    /**
     * Deletes every pack but the newest {@code keep} and {@code active}.
     *
     * @return the names deleted
     */
    List<String> prune(final String active) throws IOException {
        final List<String> deleted = new ArrayList<>();
        final List<Path> packs;
        try (Stream<Path> files = Files.list(dir)) {
            packs = files.filter(
                            file -> ZIP.matcher(file.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(PackShelf::modified).reversed())
                    .toList();
        }
        final String kept = active + ".zip";
        for (final Path old : packs.subList(Math.min(keep, packs.size()), packs.size())) {
            final String name = old.getFileName().toString();
            if (!name.equals(kept) && Files.deleteIfExists(old)) {
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
