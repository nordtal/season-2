package eu.nordtal.s2.steward.worker.backup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One volume, saved as {@code tar} + {@code zstd} into a file on this host.
 *
 * A size and a duration come back from every call, {@link SnapshotResult#saved} is only reachable with a file on
 * disk behind it, and "ok with 0 bytes" is not a state this class can produce: an empty or missing source directory
 * is a failure that names the path, never a small archive reported as a backup.
 *
 * {@code tar} and {@code zstd} are shelled out to rather than reimplemented: Java has no zstd in the JDK, a tar
 * writer in-process would be a second implementation of a format with a restore path nobody has exercised, and
 * {@code tar -xf} on the far side is what an operator will actually type when it matters. steward-worker's image is
 * a plain JRE, so both binaries have to be installed in it; without them every save here fails at {@code start()}
 * with "No such file or directory", which is at least loud.
 *
 * Creation is an explicit pipeline - {@code tar -cf - -C <source> .} into {@code zstd - -o <file>} - built with
 * {@link ProcessBuilder#startPipeline}, never through a shell and never through tar's
 * {@code --use-compress-program}, which takes one string and splits it on spaces itself. That is the same quoting
 * ambiguity {@code Console} refuses for a console line, and it is the only way to hand {@code zstd} its {@code -T0}
 * without guessing how tar will cut the string up.
 */
public final class TarSnapshots implements Snapshots {

    private static final Logger log = LoggerFactory.getLogger(TarSnapshots.class);

    /**
     * The same stamp {@code deploy/postgres-backup/backup.sh} writes on a dump.
     *
     * UTC, no separators that a filesystem or a shell glob would mind, and fixed width, so sorting the names as
     * text sorts them by time - which is what {@link #prune} relies on instead of an mtime that a copy, a restore
     * or an {@code rsync} would have rewritten.
     */
    static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    static final String SUFFIX = ".tar.zst";

    /** Written under this and renamed only once read back - see {@link #save}. */
    static final String PARTIAL = ".partial";

    /**
     * What is appended to an archive's name to mark the stop behind it as unverified.
     *
     * Beside the archive and not in it: the archive is a tar of a world directory and a restore unpacks it, so
     * anything added inside would land in somebody's world. Beside it, the mark is the first thing
     * {@code deploy/restore.sh --list} prints and the last thing a restore asks about, and the archive itself is
     * byte for byte an ordinary one.
     */
    static final String MARK = ".unverified";

    /**
     * {@code <volume>-<stamp>.tar.zst}.
     *
     * The stamp's shape is fixed and a volume name cannot contain one, so the last dash before it is the split and
     * a volume called {@code nordtal-s2_mc-smp} comes back whole.
     */
    private static final Pattern ARCHIVE =
            Pattern.compile("^(?<volume>.+)-(?<stamp>\\d{8}T\\d{6}Z)\\Q" + SUFFIX + "\\E$");

    private static final Pattern PARTIAL_ARCHIVE =
            Pattern.compile("^(?<volume>.+)-(?<stamp>\\d{8}T\\d{6}Z)\\Q" + SUFFIX + PARTIAL + "\\E$");

    /**
     * {@code nordtal-<stamp>.dump}, written by {@link DatabaseDump} and swept here.
     *
     * Why the dump is pruned by the class that tars volumes. There is one retention setting and one directory, and a
     * second sweep somewhere else would be a second number to keep in step. What it must not be is the same series:
     * counted together, fourteen files would be fourteen dumps and no world, or the reverse, depending on which was
     * written last. So the dump gets a key of its own below - one that no volume can collide with, because
     * {@link #VOLUME_NAME} forbids the space in it.
     */
    private static final Pattern DUMP = Pattern.compile(
            "^\\Q" + DatabaseDump.PREFIX + "\\E(?<stamp>\\d{8}T\\d{6}Z)\\Q" + DatabaseDump.SUFFIX + "\\E$");

    private static final Pattern PARTIAL_DUMP = Pattern.compile(
            "^\\Q" + DatabaseDump.PREFIX + "\\E(?<stamp>\\d{8}T\\d{6}Z)\\Q" + DatabaseDump.SUFFIX + PARTIAL + "\\E$");

    /** The group key the dumps are counted under. A space, so no volume name can ever be it. */
    private static final String DUMP_SERIES = "the database dump";

    /**
     * Docker's own rule for a volume name, and this class's rule too.
     *
     * The name becomes a path segment and an argv entry, so a {@code ..} or a slash in it is refused rather than
     * resolved.
     */
    private static final Pattern VOLUME_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]*");

    /**
     * Whether {@code name} is a finished archive or dump, never a {@code .partial} or {@code .unverified} one.
     *
     * By the same two patterns {@link #prune} groups files with. This alone is not the whole defence for a
     * download route: the pattern's {@code .} matches a {@code /} exactly
     * as readily as any other character, so a name that also contains a path separator can still match here. The
     * caller that resolves a path from this name must additionally check the resolved path stays inside the
     * directory it was resolved against - see {@code WorkerApi#downloadBackup} for the second half of the check.
     */
    public static boolean isFinishedArchive(final String name) {
        return ARCHIVE.matcher(name).matches() || DUMP.matcher(name).matches();
    }

    /**
     * Level 1.
     *
     * A world is mostly {@code .mca} region files, which are already deflate-compressed inside, so there is very
     * little left for zstd to find at a higher level for a real cost in extra downtime. The thing being minimised
     * here is how long the four servers are stopped, not the disk the archive lands on, so the cheapest level wins.
     * {@code -T0} uses every core for the same reason. Re-measure before changing this if the target ever becomes
     * bandwidth-bound rather than downtime-bound.
     */
    private static final String LEVEL = "-1";

    /**
     * The deadline against which a save waits, because the caller starts the servers again once this returns.
     *
     * A hung {@code tar} would otherwise be a network that never comes back up. Generous enough that no honest
     * world is near it, and short enough that a night lost to a stuck process is a night, not a week.
     */
    private static final Duration DEFAULT_WALL = Duration.ofMinutes(30);

    private final Path sourcesRoot;
    private final Path outputRoot;
    private final Clock clock;
    private final Duration wall;

    /**
     * @param sourcesRoot where the volumes are mounted read-only, one directory per volume name -
     *     {@code /backup-sources/nordtal-s2_mc-smp} and so on. Nothing in this class ever writes below it.
     * @param outputRoot where the archives are written, {@code /backups}
     * @param clock injected so the stamp in a file name is a value a test can pin, not whatever second the test
     *     happened to run in
     */
    public TarSnapshots(final Path sourcesRoot, final Path outputRoot, final Clock clock) {
        this(sourcesRoot, outputRoot, clock, DEFAULT_WALL);
    }

    /**
     * The same, with the wall set from {@code steward.yml#backup.patience-minutes}.
     *
     * How long a backup may hold the network down before it is given up on.
     */
    public TarSnapshots(final Path sourcesRoot, final Path outputRoot, final Clock clock, final Duration wall) {
        this.wall = wall;
        this.sourcesRoot = sourcesRoot;
        this.outputRoot = outputRoot;
        this.clock = clock;
    }

    @Override
    public SnapshotResult save(final String volume) {
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
        // The same move backup.sh makes for a pg_dump: a half-written archive keeps the partial name until read back.
        final Path partial = outputRoot.resolve(name + PARTIAL);

        try {
            Files.createDirectories(outputRoot);
            Files.deleteIfExists(partial);
            log.info("saving {} to {}", source, finished.getFileName());
            return writeArchive(volume, source, name, partial, finished, startedAt);
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

    private SnapshotResult writeArchive(
            final String volume,
            final Path source,
            final String name,
            final Path partial,
            final Path finished,
            final long startedAt)
            throws IOException, InterruptedException {
        final Shell created = pipeline(
                null,
                List.of(
                        // `.` and -C rather than the path, so the archive holds relative names, not absolute ones.
                        List.of("tar", "-cf", "-", "-C", source.toString(), "."),
                        List.of("zstd", LEVEL, "-T0", "-q", "-", "-o", partial.toString())));
        if (created.failed()) {
            Files.deleteIfExists(partial);
            return SnapshotResult.failed(
                    volume, since(startedAt), "tar of " + source + " failed: " + created.describe());
        }

        final String problem = unreadable(partial);
        if (problem != null) {
            Files.deleteIfExists(partial);
            return SnapshotResult.failed(
                    volume,
                    since(startedAt),
                    "the archive of " + source + " could not be read back and was discarded: " + problem);
        }

        Files.move(partial, finished);
        // The real file, asked of the filesystem: a backup that saved nothing could still report the input size.
        final long bytes = Files.size(finished);
        final Duration took = since(startedAt);
        log.info("saved {} ({}) in {}s", name, SnapshotResult.human(bytes), took.toSeconds());
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

    @Override
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
            // Not a reason to discard a backup that succeeded, but a reason to say so loudly in the log.
            log.warn("could not mark {} as unverified: {}", archive, unwritable.toString());
            return null;
        }
        log.warn("{} was taken after a stop that could not be verified: {}", mark.getFileName(), why);
        return mark.getFileName().toString();
    }

    /**
     * Applies the retention policy to each series separately - each volume, and the database dump.
     *
     * Per volume, never across: four volumes and fourteen daily copies is 56 files, not 14. A sweep that counted them
     * together would keep fourteen of whichever volume happened to be saved last and silently hold none of the other
     * three - and it would look exactly like a working retention. The age of a file is the stamp in its name, not its
     * mtime, because a file that was copied off this host and back has a new mtime and the same age.
     *
     * The database dump is one series more, counted apart from the volumes - see {@link #DUMP} for why it is swept
     * here at all and why it must not share a count with them.
     *
     * What to keep is {@link Retention}'s decision and not this method's: this one owns the directory, the naming
     * scheme and the deleting; the arithmetic, the staggered daily/weekly/monthly schedule and the one-per-day
     * collapse, is a pure function next door, where it can be checked against dates written out by hand.
     *
     * A {@code .partial} older than a day is swept too, and is the one thing here that no policy governs: it is
     * debris from a run that was killed mid-{@code tar}, it is never a backup ({@link #save} renames only after
     * reading back), and a day of grace means a save running right now is never mistaken for debris.
     */
    @Override
    public List<String> prune(final Retention policy) {
        final List<Path> files;
        try (Stream<Path> listing = Files.list(outputRoot)) {
            files = listing.filter(Files::isRegularFile).toList();
        } catch (final IOException unreadable) {
            log.warn("cannot read {} to prune it: {}", outputRoot, unreadable.toString());
            return List.of();
        }

        final Instant now = clock.instant();
        final List<String> removed = new ArrayList<>();
        final Map<String, List<Retention.Dated>> byVolume = classify(files, now, removed);

        for (final Map.Entry<String, List<Retention.Dated>> series : byVolume.entrySet()) {
            for (final Retention.Dated old : policy.expired(series.getValue(), now)) {
                final Path file = outputRoot.resolve(old.name());
                if (delete(file)) {
                    log.info(
                            "pruning {} ({} of {} remain)",
                            old.name(),
                            series.getValue().size() - 1,
                            series.getKey());
                    removed.add(old.name());
                    // The mark goes with the archive it belongs to, or it becomes a note about a file no longer there.
                    delete(file.resolveSibling(old.name() + MARK));
                }
            }
        }
        return List.copyOf(removed);
    }

    // Grouped by the volume in the name; sweeps day-old partials into `removed` as debris along the way.
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

    /**
     * One archive, dated - or nothing, if the name looked like a stamp and is not one.
     *
     * Nothing means left alone, never deleted: a file this class cannot date is a file it cannot judge the age of,
     * and the only safe thing to do with a backup you cannot judge is to keep it and say so. {@code
     * 20261301T000000Z} is the shape of it - the pattern accepts the digits and {@code LocalDateTime} refuses the
     * thirteenth month.
     */
    private static java.util.Optional<Retention.Dated> dated(final String name, final String stamp) {
        try {
            return java.util.Optional.of(new Retention.Dated(name, stampOf(stamp)));
        } catch (final DateTimeParseException notADate) {
            log.warn("leaving {} alone: {} looks like a timestamp and is not one", name, stamp);
            return java.util.Optional.empty();
        }
    }

    /**
     * Reads a finished archive back, and answers what is wrong with it or {@code null}.
     *
     * {@code zstd -t} is the cheaper check and the wrong one. It proves the frame decompresses, and a tar truncated
     * anywhere - including before its two end-of-archive blocks - decompresses perfectly. {@code tar -tf} walks the
     * member headers to the end of the stream, so it catches the truncation zstd's checksum cannot see. It is the same
     * argument {@code backup.sh} makes when it lists a dump's table of contents rather than checking its size, and it
     * costs one more read of a file that is still in the page cache.
     *
     * Package-private because it is the gate the rename in {@link #save} stands behind, and a gate nobody has watched
     * refuse anything is not a gate. Its only caller is that rename.
     */
    @Nullable
    String unreadable(final Path archive) throws IOException, InterruptedException {
        final Path listing = Files.createTempFile("snapshot-listing-", ".txt");
        try {
            final Shell read = pipeline(listing, List.of(List.of("tar", "--zstd", "-tf", archive.toString())));
            if (read.failed()) {
                return read.describe();
            }
            // An archive of nothing reads back perfectly, the same shape a truncated tar of real content can have.
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

    /**
     * Waits for every stage against one deadline, or kills the whole pipeline.
     *
     * The wall is the pipeline's, not each process's: {@code wall} is the length of time the caller may keep the
     * Minecraft servers stopped. Given to {@code waitFor} once per stage, a three-stage {@code tar | zstd | tee}
     * could take three walls to finish and still be called on time, because the later stages are slower: tar is
     * finished long before zstd has compressed what it produced.
     *
     * Everything goes when the deadline passes, not just the stage that was still running: a half-killed pipeline
     * leaves tar writing into a backup volume with nobody waiting on it.
     *
     * @return the exit codes in stage order, or empty if the wall was reached
     */
    static Optional<List<Integer>> awaitAll(final List<Process> running, final Duration wall)
            throws InterruptedException {
        final long deadline = System.nanoTime() + wall.toNanos();
        final List<Integer> codes = new ArrayList<>();
        for (final Process process : running) {
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                running.forEach(Process::destroyForcibly);
                return Optional.empty();
            }
            codes.add(process.exitValue());
        }
        return Optional.of(List.copyOf(codes));
    }

    private static Instant stampOf(final String stamp) {
        return LocalDateTime.parse(stamp, STAMP).toInstant(ZoneOffset.UTC);
    }

    /**
     * Whether a partial's stamp is older than {@code cut} - and {@code false} for one that is not a date at all.
     *
     * Matching the pattern is not the same as being a date: {@code \d{8}T\d{6}Z} accepts {@code 99999999T999999Z},
     * which {@code LocalDateTime.parse} then refuses. Thrown out of the loop, that one file would end the whole
     * retention sweep, so an unparseable stamp is left where it is and named in the log instead.
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
            // Reported, not thrown: one undeletable file must not stop the sweep from freeing the disk of the others.
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
            // A file name on a world volume need not be valid UTF-8, which must not fail this verification.
            return 1;
        }
    }

    /** What a pipeline came to: every stage's status, and whatever any of them said on stderr. */
    private record Shell(List<Integer> exitCodes, String stderr) {

        boolean failed() {
            return exitCodes.stream().anyMatch(code -> code != 0);
        }

        String describe() {
            final String said = stderr.isBlank() ? "and said nothing" : "saying: " + stderr;
            return "exit " + exitCodes + " " + said;
        }
    }

    /**
     * Runs the stages as one pipeline.
     *
     * The last one's output goes to {@code stdout} - a file when one is named, discarded otherwise. stderr is
     * collected per stage into a temporary file rather than merged into the data stream:
     * {@code redirectErrorStream} on a {@code tar -cf -} would splice tar's warnings into the archive itself,
     * producing a file that is corrupt precisely when something went wrong.
     */
    private Shell pipeline(final @Nullable Path stdout, final List<List<String>> stages)
            throws IOException, InterruptedException {
        final List<ProcessBuilder> builders = new ArrayList<>();
        final List<Path> errors = new ArrayList<>();
        for (final List<String> stage : stages) {
            final Path error = Files.createTempFile("snapshot-stderr-", ".log");
            errors.add(error);
            builders.add(new ProcessBuilder(stage).redirectError(error.toFile()));
        }
        builders.getLast()
                .redirectOutput(
                        stdout == null ? ProcessBuilder.Redirect.DISCARD : ProcessBuilder.Redirect.to(stdout.toFile()));

        List<Process> running = List.of();
        try {
            running = ProcessBuilder.startPipeline(builders);
            final Optional<List<Integer>> codes = awaitAll(running, wall);
            if (codes.isEmpty()) {
                return new Shell(
                        List.of(-1),
                        "gave up after " + wall.toMinutes() + " minutes - " + String.join(" ", stages.getFirst())
                                + " did not finish");
            }
            final StringBuilder said = new StringBuilder();
            for (final Path error : errors) {
                final String text =
                        Files.readString(error, StandardCharsets.UTF_8).strip();
                if (!text.isBlank()) {
                    said.append(said.isEmpty() ? "" : "; ").append(text);
                }
            }
            return new Shell(codes.get(), said.toString());
        } catch (final InterruptedException interrupted) {
            // A shutdown must not leave tar and zstd running into the backup volume with nobody waiting on them.
            running.forEach(Process::destroyForcibly);
            throw interrupted;
        } finally {
            for (final Path error : errors) {
                Files.deleteIfExists(error);
            }
        }
    }

    private static Duration since(final long startedAtNanos) {
        // Measured on the monotonic clock, never the injected one: the injected Clock is fixed in tests.
        return Duration.ofNanos(System.nanoTime() - startedAtNanos);
    }
}
