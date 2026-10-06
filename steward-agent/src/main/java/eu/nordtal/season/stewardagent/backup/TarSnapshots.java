package eu.nordtal.season.stewardagent.backup;

import eu.nordtal.season.database.update.ByteSize;
import eu.nordtal.season.internalapi.agent.Retention;
import eu.nordtal.season.internalapi.agent.SnapshotResult;
import eu.nordtal.season.stewardagent.measure.VolumeSizes;
import eu.nordtal.season.stewardagent.run.Snapshots;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saves one volume as {@code tar} into {@code zstd}, a file on this host, and never reports an empty archive as ok.
 *
 * The pipeline is built with {@link ProcessBuilder#startPipeline}, never through a shell or tar's compress option.
 */
public final class TarSnapshots {

    private static final Logger log = LoggerFactory.getLogger(TarSnapshots.class);

    /**
     * The stamp {@code deploy/postgres-backup/backup.sh} writes on a dump.
     *
     * UTC and fixed width, so sorting names as text sorts them by time, which {@link #prune} relies on.
     */
    static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    static final String SUFFIX = ".tar.zst";

    /** Written under this and renamed only once read back; see {@link #save}. */
    static final String PARTIAL = ".partial";

    /**
     * Appended to an archive's name to mark the stop behind it as unverified.
     *
     * The mark sits beside the archive, never inside it, since a restore would unpack it into the world.
     */
    static final String MARK = ".unverified";

    /**
     * {@code <volume>-<stamp>.tar.zst}.
     *
     * The last dash before the fixed stamp is the split, so a volume called {@code nordtal-s2_mc-smp} comes back whole.
     */
    private static final Pattern ARCHIVE =
            Pattern.compile("^(?<volume>.+)-(?<stamp>\\d{8}T\\d{6}Z)\\Q" + SUFFIX + "\\E$");

    private static final Pattern PARTIAL_ARCHIVE =
            Pattern.compile("^(?<volume>.+)-(?<stamp>\\d{8}T\\d{6}Z)\\Q" + SUFFIX + PARTIAL + "\\E$");

    /** {@code nordtal-<stamp>.dump}, written by {@link DatabaseDump} and swept here as a series of its own. */
    private static final Pattern DUMP = Pattern.compile(
            "^\\Q" + DatabaseDump.PREFIX + "\\E(?<stamp>\\d{8}T\\d{6}Z)\\Q" + DatabaseDump.SUFFIX + "\\E$");

    private static final Pattern PARTIAL_DUMP = Pattern.compile(
            "^\\Q" + DatabaseDump.PREFIX + "\\E(?<stamp>\\d{8}T\\d{6}Z)\\Q" + DatabaseDump.SUFFIX + PARTIAL + "\\E$");

    /** The group key the dumps are counted under; its space keeps any volume name from matching it. */
    private static final String DUMP_SERIES = "the database dump";

    /** Docker's rule for a volume name, which also refuses {@code ..} and slashes in a path segment. */
    private static final Pattern VOLUME_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]*");

    /**
     * Returns whether {@code name} is a finished archive or dump, never a {@code .partial} or {@code .unverified} one.
     *
     * A matching name can still contain a path separator, so a caller must also check the resolved path stays inside.
     */
    public static boolean isFinishedArchive(final String name) {
        return ARCHIVE.matcher(name).matches() || DUMP.matcher(name).matches();
    }

    /**
     * What a restore of a finished archive replaces, by the name a person types to confirm it.
     *
     * @return the volume, or the database's name for a dump; empty for anything but a finished archive
     */
    public static Optional<String> restoresInto(final String name) {
        final Matcher archive = ARCHIVE.matcher(name);
        if (archive.matches() && VOLUME_NAME.matcher(archive.group("volume")).matches()) {
            return Optional.of(archive.group("volume"));
        }
        return DUMP.matcher(name).matches() ? Optional.of(DatabaseDump.DATABASE_NAME) : Optional.empty();
    }

    /** Level 1, since region files are already compressed and downtime, not disk, is what is minimised. */
    private static final String LEVEL = "-1";

    /** The filesystem the archives are written to, as the budget and the room check read it. */
    interface Space {

        long totalBytes() throws IOException;

        /** What this process may still write, which leaves out the root reserve. */
        long usableBytes() throws IOException;

        static Space of(final Path directory) {
            return new Space() {
                @Override
                public long totalBytes() throws IOException {
                    return Files.getFileStore(directory).getTotalSpace();
                }

                @Override
                public long usableBytes() throws IOException {
                    return Files.getFileStore(directory).getUsableSpace();
                }
            };
        }
    }

    private final Path sourcesRoot;
    private final Path outputRoot;
    private final Clock clock;
    private final Space space;

    /**
     * Creates the snapshots.
     *
     * @param sourcesRoot where the volumes are mounted read-only, one directory per volume name
     * @param outputRoot where the archives are written, {@code /backups}
     * @param clock the clock that stamps a file name
     */
    public TarSnapshots(final Path sourcesRoot, final Path outputRoot, final Clock clock) {
        this(sourcesRoot, outputRoot, clock, Space.of(outputRoot));
    }

    TarSnapshots(final Path sourcesRoot, final Path outputRoot, final Clock clock, final Space space) {
        this.sourcesRoot = sourcesRoot;
        this.outputRoot = outputRoot;
        this.clock = clock;
        this.space = space;
    }

    /**
     * Saves one volume, giving up after {@code wall}, since the caller starts the servers again once it returns.
     *
     * @param volume the volume's real name, mounted under the sources root
     */
    public SnapshotResult save(final String volume, final Duration wall) {
        final long startedAt = System.nanoTime();
        if (!VOLUME_NAME.matcher(volume).matches()) {
            return SnapshotResult.failed(
                    volume,
                    since(startedAt),
                    "'" + volume + "' is not a docker volume name, and it would have become a path");
        }

        final Path source = sourcesRoot.resolve(volume);
        final SnapshotResult problem = validateSource(volume, source, startedAt);
        if (problem != null) {
            return problem;
        }

        final String name = volume + "-" + STAMP.format(clock.instant()) + SUFFIX;
        final Path finished = outputRoot.resolve(name);
        // A half-written archive keeps the partial name until read back.
        final Path partial = outputRoot.resolve(name + PARTIAL);

        try {
            Files.createDirectories(outputRoot);
            Files.deleteIfExists(partial);
            log.info("saving {} to {}", source, finished.getFileName());
            return writeArchive(volume, source, new Target(name, partial, finished), startedAt, wall);
        } catch (final IOException failure) {
            quietlyDelete(partial);
            return SnapshotResult.failed(volume, since(startedAt), "saving " + source + " failed: " + failure);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            quietlyDelete(partial);
            return SnapshotResult.failed(
                    volume, since(startedAt), "saving " + source + " was interrupted - no archive was written");
        }
    }

    /**
     * The series a finished archive in the backups belongs to: its volume, or {@link Snapshots#DATABASE} for a dump.
     *
     * @return empty for a name that is no finished archive, or one that is not there
     */
    public Optional<String> seriesOf(final String name) {
        final Path file;
        try {
            file = outputRoot.resolve(name).normalize();
        } catch (final InvalidPathException notAName) {
            return Optional.empty();
        }
        if (!outputRoot.normalize().equals(file.getParent()) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        final Matcher archive = ARCHIVE.matcher(name);
        if (archive.matches()) {
            return Optional.of(archive.group("volume"));
        }
        return DUMP.matcher(name).matches() ? Optional.of(Snapshots.DATABASE) : Optional.empty();
    }

    /**
     * Puts a volume archive back: reads it through, empties the volume and unpacks it, owners and modes kept.
     *
     * Every server on the volume has to be stopped; a failure after the emptying leaves the volume incomplete.
     */
    public Snapshots.Restored restore(final String archive, final Duration wall) {
        final long startedAt = System.nanoTime();
        final String volume = seriesOf(archive).orElse(null);
        if (volume == null
                || Snapshots.DATABASE.equals(volume)
                || !VOLUME_NAME.matcher(volume).matches()) {
            return untouched(SnapshotResult.failed(
                    archive, since(startedAt), archive + " is not a volume archive in the backups"));
        }
        final Path file = outputRoot.resolve(archive);
        final Path target = sourcesRoot.resolve(volume);
        if (!Files.isDirectory(target)) {
            return untouched(SnapshotResult.failed(
                    volume, since(startedAt), "no such directory to restore into: " + target + " - is it mounted?"));
        }
        boolean touched = false;
        try {
            final String problem = unreadable(file, wall);
            if (problem != null) {
                return untouched(SnapshotResult.failed(
                        volume,
                        since(startedAt),
                        archive + " could not be read through, so nothing was touched: " + problem));
            }
            touched = true;
            empty(target, Files::delete);
            log.info("unpacking {} into {}", archive, target);
            final Pipeline.Result unpacked = Pipeline.run(
                    wall,
                    null,
                    List.of(
                            List.of("zstd", "-dc", "-q", file.toString()),
                            List.of("tar", "-xpf", "-", "--numeric-owner", "-C", target.toString())));
            if (unpacked.failed()) {
                return new Snapshots.Restored(
                        SnapshotResult.failed(
                                volume,
                                since(startedAt),
                                "unpacking " + archive + " failed and left " + volume + " incomplete: "
                                        + unpacked.describe()),
                        true);
            }
            return new Snapshots.Restored(
                    SnapshotResult.saved(volume, Files.size(file), since(startedAt), file.toString()), true);
        } catch (final IOException failure) {
            return new Snapshots.Restored(
                    SnapshotResult.failed(volume, since(startedAt), "restoring " + archive + " failed: " + failure),
                    touched);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Snapshots.Restored(
                    SnapshotResult.failed(volume, since(startedAt), "restoring " + archive + " was interrupted"),
                    touched);
        }
    }

    private static Snapshots.Restored untouched(final SnapshotResult failed) {
        return new Snapshots.Restored(failed, false);
    }

    /**
     * Deletes everything inside {@code directory} and keeps the directory, which is a mount point.
     *
     * An empty directory another mount sits on, as a server's {@code plugins/}, is kept: the kernel refuses it as busy.
     */
    static void empty(final Path directory, final Deleter deleter) throws IOException {
        try (Stream<Path> inside = Files.walk(directory)) {
            for (final Path path :
                    inside.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (path.equals(directory)) {
                    continue;
                }
                try {
                    deleter.delete(path);
                } catch (final FileSystemException refused) {
                    if (!BUSY.equals(refused.getReason()) || !isEmptyDirectory(path)) {
                        throw refused;
                    }
                }
            }
        }
    }

    /** What the kernel's EBUSY reads as in a {@link FileSystemException}. */
    private static final String BUSY = "Device or resource busy";

    private static boolean isEmptyDirectory(final Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            return false;
        }
        try (Stream<Path> inside = Files.list(path)) {
            return inside.findAny().isEmpty();
        }
    }

    /** Deletes one path, as {@link Files#delete} does. */
    @FunctionalInterface
    interface Deleter {
        void delete(Path path) throws IOException;
    }

    /** Where one archive is written: its name, the partial file, and the name it gets once read back. */
    private record Target(String name, Path partial, Path finished) {}

    private SnapshotResult writeArchive(
            final String volume, final Path source, final Target target, final long startedAt, final Duration wall)
            throws IOException, InterruptedException {
        final String name = target.name();
        final Path partial = target.partial();
        final Path finished = target.finished();
        final Pipeline.Result created = Pipeline.run(
                wall,
                null,
                List.of(
                        // `.` and -C so the archive holds relative names.
                        List.of("tar", "-cf", "-", "-C", source.toString(), "."),
                        // --rsyncable keeps an unchanged region's bytes alike, so the offsite copy deduplicates it.
                        List.of("zstd", LEVEL, "-T0", "--rsyncable", "-q", "-", "-o", partial.toString())));
        if (created.failed()) {
            Files.deleteIfExists(partial);
            return SnapshotResult.failed(
                    volume, since(startedAt), "tar of " + source + " failed: " + created.describe());
        }

        final String problem = unreadable(partial, wall);
        if (problem != null) {
            Files.deleteIfExists(partial);
            return SnapshotResult.failed(
                    volume,
                    since(startedAt),
                    "the archive of " + source + " could not be read back and was discarded: " + problem);
        }

        Files.move(partial, finished);
        // The real size on disk, not the input size.
        final long bytes = Files.size(finished);
        final Duration took = since(startedAt);
        log.info("saved {} ({}) in {}s", name, ByteSize.of(bytes), took.toSeconds());
        return SnapshotResult.saved(volume, bytes, took, finished.toString());
    }

    // A missing or empty source directory is a failure that names the path, never a small archive as a backup.
    private @Nullable SnapshotResult validateSource(final String volume, final Path source, final long startedAt) {
        if (!Files.isDirectory(source)) {
            return SnapshotResult.failed(
                    volume,
                    since(startedAt),
                    "no such directory to save: " + source + " - is the volume mounted here?");
        }
        try (Stream<Path> entries = Files.list(source)) {
            if (entries.findAny().isEmpty()) {
                return SnapshotResult.failed(
                        volume,
                        since(startedAt),
                        "nothing to save: " + source + " is empty - an empty archive is not a backup");
            }
        } catch (final IOException unreadable) {
            return SnapshotResult.failed(
                    volume, since(startedAt), "cannot read " + source + ": " + unreadable.getMessage());
        }
        return null;
    }

    /** Marks an archive as saved behind a stop nobody could verify, and answers the mark's name or {@code null}. */
    public @Nullable String markUnverified(final String archive, final String why) {
        final Path mark;
        try {
            mark = Path.of(archive + MARK);
        } catch (final InvalidPathException notAPath) {
            log.warn("cannot mark {}: {}", archive, notAPath.toString());
            return null;
        }
        try {
            Files.writeString(mark, why + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (final IOException unwritable) {
            // Not a reason to discard a backup that succeeded, but logged loudly.
            log.warn("could not mark {} as unverified: {}", archive, unwritable.toString());
            return null;
        }
        log.warn("{} was taken after a stop that could not be verified: {}", mark.getFileName(), why);
        return mark.getFileName().toString();
    }

    /**
     * Applies the retention policy to each volume and to the database dump as separate series, then the disk budget.
     * A volume outside {@code inBackup} is counted by the calendar; a day-old {@code .partial} is swept too.
     *
     * @param inBackup the volumes a backup saves now, by the name an archive carries
     * @param budgetPercent the share of the filesystem the finished archives may take together
     */
    public Snapshots.Pruned prune(final Retention policy, final Collection<String> inBackup, final int budgetPercent) {
        final List<Path> files;
        try (Stream<Path> listing = Files.list(outputRoot)) {
            files = listing.filter(Files::isRegularFile).toList();
        } catch (final IOException unreadable) {
            log.warn("cannot read {} to prune it: {}", outputRoot, unreadable.toString());
            return new Snapshots.Pruned(List.of(), List.of());
        }

        final Instant now = clock.instant();
        final List<String> removed = new ArrayList<>();
        final Map<String, List<Retention.Dated>> byVolume = classify(files, now, removed);
        final List<DiskBudget.Held> left = new ArrayList<>();

        for (final Map.Entry<String, List<Retention.Dated>> series : byVolume.entrySet()) {
            final boolean taken = DUMP_SERIES.equals(series.getKey()) || inBackup.contains(series.getKey());
            final List<Retention.Dated> expired =
                    taken ? policy.expired(series.getValue(), now) : policy.expiredByCalendar(series.getValue(), now);
            for (final Retention.Dated old : expired) {
                if (deleteArchive(old.name())) {
                    log.info(
                            "pruning {} ({} of {} remain)",
                            old.name(),
                            series.getValue().size() - 1,
                            series.getKey());
                    removed.add(old.name());
                }
            }
            series.getValue().stream()
                    .filter(one -> !removed.contains(one.name()))
                    .forEach(one -> held(series.getKey(), one).ifPresent(left::add));
        }
        return new Snapshots.Pruned(removed, overBudget(left, budgetPercent));
    }

    /**
     * What a backup of {@code volumes} and the database would write against the room on the disk.
     *
     * A filesystem that cannot be read counts as room, since the save itself still fails on a full disk.
     */
    public Snapshots.Room room(final Collection<String> volumes, final int keepFreePercent) {
        final Map<String, Long> newest = new LinkedHashMap<>();
        try (Stream<Path> listing = Files.list(outputRoot)) {
            for (final Path file : listing.filter(Files::isRegularFile).sorted().toList()) {
                final String name = file.getFileName().toString();
                final Matcher archive = ARCHIVE.matcher(name);
                final String series = archive.matches()
                        ? archive.group("volume")
                        : DUMP.matcher(name).matches() ? DUMP_SERIES : null;
                if (series != null) {
                    // Sorted by name, so the stamp puts the newest last.
                    newest.put(series, Files.size(file));
                }
            }
        } catch (final IOException unreadable) {
            log.warn("cannot read {} to size the backup: {}", outputRoot, unreadable.toString());
        }
        long expected = newest.getOrDefault(DUMP_SERIES, 0L);
        for (final String volume : volumes) {
            final Long last = newest.get(volume);
            expected += last != null
                    ? last
                    : VolumeSizes.du(sourcesRoot.resolve(volume)).orElse(0L);
        }
        try {
            final long total = space.totalBytes();
            return new Snapshots.Room(expected, space.usableBytes(), total / 100 * keepFreePercent);
        } catch (final IOException unreadable) {
            log.warn("cannot read the free space under {}: {}", outputRoot, unreadable.toString());
            return new Snapshots.Room(expected, Long.MAX_VALUE, 0);
        }
    }

    /** Deletes what the budget cannot hold; nothing when the filesystem cannot be read. */
    private List<String> overBudget(final List<DiskBudget.Held> left, final int budgetPercent) {
        final long total;
        try {
            total = space.totalBytes();
        } catch (final IOException unreadable) {
            log.warn("cannot read the size of {} for the disk budget: {}", outputRoot, unreadable.toString());
            return List.of();
        }
        final List<String> removed = new ArrayList<>();
        for (final String name : DiskBudget.of(total, budgetPercent).over(left)) {
            if (deleteArchive(name)) {
                log.info("pruning {} - the archives take more than {} percent of the disk", name, budgetPercent);
                removed.add(name);
            }
        }
        return List.copyOf(removed);
    }

    /** One archive as the budget weighs it; empty when it vanished meanwhile. */
    private Optional<DiskBudget.Held> held(final String series, final Retention.Dated archive) {
        final Path file = outputRoot.resolve(archive.name());
        try {
            return Optional.of(new DiskBudget.Held(
                    archive.name(),
                    series,
                    archive.taken(),
                    Files.size(file),
                    !Files.exists(file.resolveSibling(archive.name() + MARK))));
        } catch (final IOException gone) {
            return Optional.empty();
        }
    }

    /** Deletes an archive and, with it, its mark. */
    private boolean deleteArchive(final String name) {
        final Path file = outputRoot.resolve(name);
        if (!delete(file)) {
            return false;
        }
        delete(file.resolveSibling(name + MARK));
        return true;
    }

    // Groups by the volume in the name and sweeps day-old partials into `removed`.
    private Map<String, List<Retention.Dated>> classify(
            final List<Path> files, final Instant now, final List<String> removed) {
        final Map<String, List<Retention.Dated>> byVolume = new LinkedHashMap<>();
        final Instant debrisBefore = now.minus(Duration.ofDays(1));
        for (final Path file : files) {
            final String name = file.getFileName().toString();
            final Matcher archive = ARCHIVE.matcher(name);
            if (archive.matches()) {
                dated(name, archive.group("stamp"))
                        .ifPresent(
                                one -> byVolume.computeIfAbsent(archive.group("volume"), ignored -> new ArrayList<>())
                                        .add(one));
                continue;
            }
            final Matcher dump = DUMP.matcher(name);
            if (dump.matches()) {
                dated(name, dump.group("stamp"))
                        .ifPresent(one -> byVolume.computeIfAbsent(DUMP_SERIES, ignored -> new ArrayList<>())
                                .add(one));
                continue;
            }
            final Matcher leftover = PARTIAL_ARCHIVE.matcher(name);
            if (leftover.matches() && isOlderThan(leftover.group("stamp"), debrisBefore, name)) {
                if (delete(file)) {
                    log.info("pruning {} - a partial from a killed run, never a backup", name);
                    removed.add(name);
                }
                continue;
            }
            final Matcher halfDump = PARTIAL_DUMP.matcher(name);
            if (halfDump.matches() && isOlderThan(halfDump.group("stamp"), debrisBefore, name)) {
                if (delete(file)) {
                    log.info("pruning {} - a partial dump from a killed run, never a backup", name);
                    removed.add(name);
                }
            }
        }
        return byVolume;
    }

    /** Returns one archive dated, or empty when the stamp is not a real date, which leaves the file alone. */
    private static java.util.Optional<Retention.Dated> dated(final String name, final String stamp) {
        try {
            return java.util.Optional.of(new Retention.Dated(name, stampOf(stamp)));
        } catch (final DateTimeParseException notADate) {
            log.warn("leaving {} alone: {} looks like a timestamp and is not one", name, stamp);
            return java.util.Optional.empty();
        }
    }

    /**
     * Reads a finished archive back and returns what is wrong with it, or {@code null}.
     *
     * {@code tar -tf} walks every header, so it catches a truncation that {@code zstd -t} would pass.
     */
    @Nullable
    String unreadable(final Path archive, final Duration wall) throws IOException, InterruptedException {
        final Path listing = Files.createTempFile("snapshot-listing-", ".txt");
        try {
            final Pipeline.Result read =
                    Pipeline.run(wall, listing, List.of(List.of("tar", "--zstd", "-tf", archive.toString())));
            if (read.failed()) {
                return read.describe();
            }
            // An archive of nothing reads back perfectly, just like a truncated one.
            final long members = countLines(listing);
            if (members == 0) {
                return "it holds no files at all";
            }
            log.debug("{} holds {} entries", archive.getFileName(), members);
            return null;
        } finally {
            Files.deleteIfExists(listing);
        }
    }

    private static Instant stampOf(final String stamp) {
        return LocalDateTime.parse(stamp, STAMP).toInstant(ZoneOffset.UTC);
    }

    /**
     * Returns whether a partial's stamp is older than {@code cut}, and {@code false} for a stamp that is not a date.
     */
    private static boolean isOlderThan(final String stamp, final Instant cut, final String name) {
        try {
            return stampOf(stamp).isBefore(cut);
        } catch (final DateTimeParseException notADate) {
            log.warn("leaving {} alone: {} looks like a timestamp and is not one", name, stamp);
            return false;
        }
    }

    private static boolean delete(final Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (final IOException undeletable) {
            // Reported, not thrown, so one undeletable file does not stop the sweep.
            log.warn("cannot delete {}: {}", file, undeletable.toString());
            return false;
        }
    }

    private void quietlyDelete(final Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (final IOException ignored) {
            log.warn("a partial archive is left behind at {} - the next prune will take it", file);
        }
    }

    private static long countLines(final Path listing) throws IOException {
        try (Stream<String> lines = Files.lines(listing, StandardCharsets.UTF_8)) {
            return lines.count();
        } catch (final UncheckedIOException undecodable) {
            // A file name on a world volume need not be valid UTF-8.
            return 1;
        }
    }

    private static Duration since(final long startedAtNanos) {
        // The monotonic clock, since the injected one is fixed in tests.
        return Duration.ofNanos(System.nanoTime() - startedAtNanos);
    }
}
