package eu.nordtal.s2.steward.worker.backup;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * §9a's half of the saving: one volume, {@code tar} + {@code zstd}, into a file on this host.
 *
 * <h2>Why this exists at all</h2>
 * It replaces asking a management panel to snapshot a volume over its REST API. On 2026-09-12 run 23 stopped
 * {@code smp}, {@code network-control} and {@code discord-bot}, lost <em>every</em> volume snapshot
 * to {@code HTTP 403}, started them again and reported success - 66 seconds of network down and
 * zero backups ({@code todo.md} A23). Nothing in that report made the zero visible, because nothing
 * in it was a size. So the shape here is the opposite one: a size and a duration come back from
 * every call, {@link SnapshotResult#saved} is only reachable with a file on disk behind it, and
 * "ok with 0 bytes" is not a state this class can produce.
 *
 * <h2>The binaries, and what that costs the image</h2>
 * {@code tar} and {@code zstd} are shelled out to rather than reimplemented: Java has no zstd in the
 * JDK, a tar writer in-process would be a second implementation of a format with a restore path
 * nobody has exercised, and {@code tar -xf} on the far side is what an operator will actually type
 * at 04:45 when it matters.
 *
 * <p><b>steward-worker's image is a plain JRE, so both binaries have to be installed in it.</b>
 * {@code steward-worker/Dockerfile} needs {@code tar} and {@code zstd} - without them every save
 * here fails at {@code start()} with "No such file or directory", which is at least loud, but it is
 * loud at 04:45 on a night nobody is watching. That is a deployment step as much as this is a
 * commit.</p>
 *
 * <h2>Two processes, not one string</h2>
 * Creation is an explicit pipeline - {@code tar -cf - -C <source> .} into {@code zstd - -o <file>} -
 * built with {@link ProcessBuilder#startPipeline}, never through a shell and never through tar's
 * {@code --use-compress-program}, which takes <em>one string</em> and splits it on spaces itself.
 * That is the same quoting ambiguity {@code Console} refuses for a console line, and it is the only
 * way to hand {@code zstd} its {@code -T0} without guessing how tar will cut the string up.
 */
public final class TarSnapshots implements Snapshots {

    private static final Logger log = LoggerFactory.getLogger(TarSnapshots.class);

    /**
     * The same stamp {@code deploy/postgres-backup/backup.sh} writes on a dump: UTC, no separators
     * that a filesystem or a shell glob would mind, and fixed width - so sorting the names as text
     * sorts them by time, which is what {@link #prune} relies on instead of an mtime that a copy,
     * a restore or an {@code rsync} would have rewritten.
     */
    static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    static final String SUFFIX = ".tar.zst";

    /** Written under this and renamed only once read back - see {@link #save}. */
    static final String PARTIAL = ".partial";

    /**
     * What is appended to an archive's name to mark the stop behind it as unverified.
     *
     * <p>Beside the archive and not in it: the archive is a tar of a world directory and a restore
     * unpacks it, so anything added inside would land in somebody's world. Beside it, the mark is
     * the first thing {@code deploy/restore.sh --list} prints and the last thing a restore asks
     * about, and the archive itself is byte for byte an ordinary one.</p>
     */
    static final String MARK = ".unverified";

    /**
     * {@code <volume>-<stamp>.tar.zst}. The stamp's shape is fixed and a volume name cannot contain
     * one, so the last dash before it is the split and a volume called {@code nordtal-s2_mc-smp}
     * comes back whole.
     */
    private static final Pattern ARCHIVE =
            Pattern.compile("^(?<volume>.+)-(?<stamp>\\d{8}T\\d{6}Z)\\Q" + SUFFIX + "\\E$");

    private static final Pattern PARTIAL_ARCHIVE =
            Pattern.compile("^(?<volume>.+)-(?<stamp>\\d{8}T\\d{6}Z)\\Q" + SUFFIX + PARTIAL + "\\E$");

    /**
     * Docker's own rule for a volume name, and this class's rule too. The name becomes a path
     * segment and an argv entry, so a {@code ..} or a slash in it is refused rather than resolved.
     */
    private static final Pattern VOLUME_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]*");

    /**
     * <b>Level 1, and that is measured, not assumed.</b> On the real {@code nordtal-s2_mc-smp} of
     * this host - 655 MiB - on 2026-09-13:
     *
     * <pre>
     *   -1   2.4 s   512.9 MiB
     *   -3   5.3 s   509.8 MiB
     *   -9   7.7 s   507.2 MiB
     * </pre>
     *
     * <p>A world is mostly {@code .mca} region files, which are already deflate-compressed inside,
     * so there is very little left for zstd to find: three seconds of extra downtime buy 0.6 % of
     * the size. The thing being minimised here is how long the four servers are stopped, not the
     * disk the archive lands on, so the cheapest level wins. {@code -T0} uses every core for
     * exactly the same reason. If the target ever becomes bandwidth-bound rather than
     * downtime-bound - the Storage Box upload of §9a - re-measure before changing this.</p>
     */
    private static final String LEVEL = "-1";

    /**
     * A hung {@code tar} is a network that never comes back up, because the caller starts the
     * servers again after this returns. So there is a wall: generous enough that no honest world is
     * near it (657 MiB takes seconds on dev), short enough that a night lost to a stuck process is
     * a night, not a week.
     */
    private static final Duration DEFAULT_WALL = Duration.ofMinutes(30);

    private final Path sourcesRoot;
    private final Path outputRoot;
    private final Clock clock;
    private final Duration wall;

    /**
     * @param sourcesRoot where the volumes are mounted <b>read-only</b>, one directory per volume
     *                    name - {@code /backup-sources/nordtal-s2_mc-smp} and so on. Read-only is
     *                    the whole reason §9a could argue the added rights are smaller than they
     *                    sound, and nothing in this class ever writes below it.
     * @param outputRoot  where the archives are written, {@code /backups}
     * @param clock       injected so the stamp in a file name is a value a test can pin, not
     *                    whatever second the test happened to run in
     */
    public TarSnapshots(final @NotNull Path sourcesRoot, final @NotNull Path outputRoot,
                        final @NotNull Clock clock) {
        this(sourcesRoot, outputRoot, clock, DEFAULT_WALL);
    }

    /**
     * The same, with the wall set from {@code steward.yml#backup.patience-minutes}.
     *
     * <p>That key is older than this class - it used to be how long the run waited for a snapshot
     * it had asked somebody else to take. The waiting is local now, but the question the
     * number answers is the same one and there is no reason to ask it twice: how long may a
     * backup hold the network down before it is given up on.</p>
     */
    public TarSnapshots(final @NotNull Path sourcesRoot, final @NotNull Path outputRoot,
                        final @NotNull Clock clock, final @NotNull Duration wall) {
        this.wall = wall;
        this.sourcesRoot = sourcesRoot;
        this.outputRoot = outputRoot;
        this.clock = clock;
    }

    @Override
    public @NotNull SnapshotResult save(final @NotNull String volume) {
        final long startedAt = System.nanoTime();
        if (!VOLUME_NAME.matcher(volume).matches()) {
            return SnapshotResult.failed(volume, since(startedAt),
                    "'" + volume + "' is not a docker volume name, and it would have become a path");
        }

        final Path source = sourcesRoot.resolve(volume);
        // A23, restated as code: a directory that is not there, and a directory with nothing in it,
        // are FAILURES that name the path - not a 45-byte archive of nothing reported as a backup.
        // An empty world directory means the mount is missing or the volume is the wrong one, and
        // both of those are exactly the silence that let run 23 pass.
        if (!Files.isDirectory(source)) {
            return SnapshotResult.failed(volume, since(startedAt),
                    "no such directory to save: " + source + " - is the volume mounted here?");
        }
        try (Stream<Path> entries = Files.list(source)) {
            if (entries.findAny().isEmpty()) {
                return SnapshotResult.failed(volume, since(startedAt),
                        "nothing to save: " + source + " is empty - an empty archive is not a backup");
            }
        } catch (final IOException unreadable) {
            return SnapshotResult.failed(volume, since(startedAt),
                    "cannot read " + source + ": " + unreadable.getMessage());
        }

        final String name = volume + "-" + STAMP.format(clock.instant()) + SUFFIX;
        final Path finished = outputRoot.resolve(name);
        // The same move backup.sh makes for a pg_dump, for the same reason it gives: a half-written
        // archive that is named like every other archive in the directory is worse than no archive,
        // because it is the one the retention sweep keeps and the one a restore picks. It carries
        // the partial name until it has been read back, and only then takes the real one.
        final Path partial = outputRoot.resolve(name + PARTIAL);

        try {
            Files.createDirectories(outputRoot);
            Files.deleteIfExists(partial);

            log.info("saving {} to {}", source, finished.getFileName());
            final Shell created = pipeline(null, List.of(
                    // `.` and -C rather than the path, so the archive holds relative names and
                    // cannot be extracted over an absolute path somewhere else on the host.
                    List.of("tar", "-cf", "-", "-C", source.toString(), "."),
                    List.of("zstd", LEVEL, "-T0", "-q", "-", "-o", partial.toString())));
            if (created.failed()) {
                Files.deleteIfExists(partial);
                return SnapshotResult.failed(volume, since(startedAt),
                        "tar of " + source + " failed: " + created.describe());
            }

            final String problem = unreadable(partial);
            if (problem != null) {
                Files.deleteIfExists(partial);
                return SnapshotResult.failed(volume, since(startedAt),
                        "the archive of " + source + " could not be read back and was discarded: "
                                + problem);
            }

            Files.move(partial, finished);
            // The real file, asked of the filesystem - not the sum of what was fed to tar, which is
            // what a backup that saved nothing would happily report.
            final long bytes = Files.size(finished);
            final Duration took = since(startedAt);
            log.info("saved {} ({}) in {}s", name, SnapshotResult.human(bytes), took.toSeconds());
            return SnapshotResult.saved(volume, bytes, took, finished.toString());
        } catch (final IOException failure) {
            quietlyDelete(partial);
            return SnapshotResult.failed(volume, since(startedAt),
                    "saving " + source + " failed: " + failure);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            quietlyDelete(partial);
            return SnapshotResult.failed(volume, since(startedAt),
                    "saving " + source + " was interrupted - no archive was written");
        }
    }

    @Override
    public @Nullable String markUnverified(final @NotNull String archive, final @NotNull String why) {
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
            // Not a reason to discard a backup that succeeded. It is a reason to say so loudly in
            // the log, because the one thing worse than an unverified archive is an unverified
            // archive nobody can tell apart from a good one.
            log.warn("could not mark {} as unverified: {}", archive, unwritable.toString());
            return null;
        }
        log.warn("{} was taken after a stop that could not be verified: {}",
                mark.getFileName(), why);
        return mark.getFileName().toString();
    }

    /**
     * Keeps the newest {@code keep} archives <b>of each volume</b>.
     *
     * <p>Per volume, never across: four volumes and {@code keep} 7 is 28 files, not 7. A sweep that
     * counted them together would keep seven of whichever volume happened to be saved last and
     * silently hold none of the other three - and it would look exactly like a working retention.
     * The ordering is the stamp in the name, not the mtime, because a file that was copied off this
     * host and back has a new mtime and the same age.</p>
     *
     * <p>A {@code .partial} older than a day is swept too, and is the one thing here that is
     * deleted without being counted against {@code keep}: it is debris from a run that was killed
     * mid-{@code tar}, it is never a backup ({@link #save} renames only after reading back), and a
     * day of grace means a save running right now is never mistaken for debris.</p>
     */
    @Override
    public @NotNull List<String> prune(final int keep) {
        if (keep < 1) {
            // Refused rather than obeyed: a retention of zero deletes every backup there is, and
            // the likeliest way to arrive here is an unset config value read as 0.
            throw new IllegalArgumentException("keep must be at least 1, was " + keep);
        }
        final List<String> removed = new ArrayList<>();
        final List<Path> files;
        try (Stream<Path> listing = Files.list(outputRoot)) {
            files = listing.filter(Files::isRegularFile).toList();
        } catch (final IOException unreadable) {
            log.warn("cannot read {} to prune it: {}", outputRoot, unreadable.toString());
            return List.of();
        }

        // Grouped by the volume in the name, so only files this class itself named are ever
        // considered - the same guarantee backup.sh gives by globbing its own prefix. Anything an
        // operator dropped in the directory by hand matches neither pattern and is left alone.
        final Map<String, List<Path>> byVolume = new LinkedHashMap<>();
        final Instant debrisBefore = clock.instant().minus(Duration.ofDays(1));
        for (final Path file : files) {
            final String name = file.getFileName().toString();
            final Matcher archive = ARCHIVE.matcher(name);
            if (archive.matches()) {
                byVolume.computeIfAbsent(archive.group("volume"), ignored -> new ArrayList<>()).add(file);
                continue;
            }
            final Matcher leftover = PARTIAL_ARCHIVE.matcher(name);
            if (leftover.matches() && isOlderThan(leftover.group("stamp"), debrisBefore, name)) {
                if (delete(file)) {
                    log.info("pruning {} - a partial from a killed run, never a backup", name);
                    removed.add(name);
                }
            }
        }

        for (final Map.Entry<String, List<Path>> volume : byVolume.entrySet()) {
            final List<Path> newestFirst = volume.getValue().stream()
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .toList();
            final int keptHere = Math.min(keep, newestFirst.size());
            for (final Path old : newestFirst.subList(keptHere, newestFirst.size())) {
                final String name = old.getFileName().toString();
                if (delete(old)) {
                    log.info("pruning {} (keeping {} of {})", name, keep, volume.getKey());
                    removed.add(name);
                    // The mark goes with the archive it belongs to. Left behind it would be a
                    // warning about a file that is no longer there, which is how a directory fills
                    // up with notes nobody can act on.
                    delete(old.resolveSibling(name + MARK));
                }
            }
        }
        return List.copyOf(removed);
    }

    /**
     * Reads a finished archive back, and answers what is wrong with it or {@code null}.
     *
     * <p><b>{@code zstd -t} is the cheaper check and the wrong one.</b> It proves the frame
     * decompresses, and a tar truncated anywhere - including before its two end-of-archive blocks -
     * decompresses perfectly. {@code tar -tf} walks the member headers to the end of the stream, so
     * it catches the truncation zstd's checksum cannot see. It is the same argument
     * {@code backup.sh} makes when it lists a dump's table of contents rather than checking its
     * size, and it costs one more read of a file that is still in the page cache.</p>
     *
     * <p>Package-private because it is the gate the rename in {@link #save} stands behind, and a
     * gate nobody has watched refuse anything is not a gate. Its only caller is that rename.</p>
     */
    String unreadable(final @NotNull Path archive) throws IOException, InterruptedException {
        final Path listing = Files.createTempFile("snapshot-listing-", ".txt");
        try {
            final Shell read = pipeline(listing, List.of(
                    List.of("tar", "--zstd", "-tf", archive.toString())));
            if (read.failed()) {
                return read.describe();
            }
            // An archive of nothing reads back perfectly. That is precisely the 45-byte file A23
            // would have called a backup, so an empty listing is a problem and not a curiosity.
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
     * Waits for every stage against <b>one</b> deadline, or kills the whole pipeline.
     *
     * <h2>The wall is the pipeline's, not each process's</h2>
     * {@code wall} is the length of time the caller may keep the Minecraft servers stopped. Given
     * to {@code waitFor} once per stage, a three-stage {@code tar | zstd | tee} could take three
     * walls to finish and still be called on time - and it is the <i>later</i> stages that are slow,
     * because tar is finished long before zstd has compressed what it produced. A config saying
     * twenty minutes could stop the network for an hour, and every report would say it kept to the
     * limit.
     *
     * <p>Everything goes when the deadline passes, not just the stage that was still running: a
     * half-killed pipeline leaves tar writing into a backup volume with nobody waiting on it.</p>
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
     * Whether a partial's stamp is older than {@code cut} - and {@code false} for one that is not a
     * date at all.
     *
     * <p><b>Matching the pattern is not the same as being a date.</b> {@code \d{8}T\d{6}Z} accepts
     * {@code 99999999T999999Z}, which {@code LocalDateTime.parse} then refuses. Thrown out of the
     * loop, that one file ended the whole retention sweep: every volume after it kept every archive
     * it had, the backup volume filled up over weeks, and the only sign was one stack trace in a log
     * on a run that otherwise said it had succeeded. So the file is left where it is and named in
     * the log - it is one unexplained file, and the sweep is the thing that has to keep going.</p>
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
            // Reported, not thrown: one undeletable file must not stop the sweep from freeing the
            // disk of the others, and a retention that fails silently is the failure mode Snapshots
            // asks this method to return a list against.
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
            // A file name on a world volume need not be valid UTF-8. That says nothing about the
            // archive, so it must not fail the verification - tar's exit status already has.
            return 1;
        }
    }

    /** What a pipeline came to: every stage's status, and whatever any of them said on stderr. */
    private record Shell(@NotNull List<Integer> exitCodes, @NotNull String stderr) {

        boolean failed() {
            return exitCodes.stream().anyMatch(code -> code != 0);
        }

        String describe() {
            final String said = stderr.isBlank() ? "and said nothing" : "saying: " + stderr;
            return "exit " + exitCodes + " " + said;
        }
    }

    /**
     * Runs the stages as one pipeline, with the last one's output going to {@code stdout} - a file
     * when one is named, discarded otherwise.
     *
     * <p>stderr is collected per stage into a temporary file rather than merged into the data
     * stream: {@code redirectErrorStream} on a {@code tar -cf -} would splice tar's warnings into
     * the archive itself, producing a file that is corrupt precisely when something went wrong.</p>
     */
    private Shell pipeline(final Path stdout, final List<List<String>> stages)
            throws IOException, InterruptedException {
        final List<ProcessBuilder> builders = new ArrayList<>();
        final List<Path> errors = new ArrayList<>();
        for (final List<String> stage : stages) {
            final Path error = Files.createTempFile("snapshot-stderr-", ".log");
            errors.add(error);
            builders.add(new ProcessBuilder(stage).redirectError(error.toFile()));
        }
        builders.getLast().redirectOutput(stdout == null
                ? ProcessBuilder.Redirect.DISCARD : ProcessBuilder.Redirect.to(stdout.toFile()));

        List<Process> running = List.of();
        try {
            running = ProcessBuilder.startPipeline(builders);
            final Optional<List<Integer>> codes = awaitAll(running, wall);
            if (codes.isEmpty()) {
                return new Shell(List.of(-1), "gave up after " + wall.toMinutes()
                        + " minutes - " + String.join(" ", stages.getFirst()) + " did not finish");
            }
            final StringBuilder said = new StringBuilder();
            for (final Path error : errors) {
                final String text = Files.readString(error, StandardCharsets.UTF_8).strip();
                if (!text.isBlank()) {
                    said.append(said.isEmpty() ? "" : "; ").append(text);
                }
            }
            return new Shell(codes.get(), said.toString());
        } catch (final InterruptedException interrupted) {
            // A shutdown while tar is running must not leave tar and zstd behind writing into the
            // backup volume with nobody waiting on them. They go first, then the interrupt travels.
            running.forEach(Process::destroyForcibly);
            throw interrupted;
        } finally {
            for (final Path error : errors) {
                Files.deleteIfExists(error);
            }
        }
    }

    private static Duration since(final long startedAtNanos) {
        // Measured on the monotonic clock, never on the injected one: the injected Clock is fixed
        // in tests and would report every run as instant, and a wall clock that steps backwards
        // over an NTP correction would report a backup as having taken negative time.
        return Duration.ofNanos(System.nanoTime() - startedAtNanos);
    }
}
