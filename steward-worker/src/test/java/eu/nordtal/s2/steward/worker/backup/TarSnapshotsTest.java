package eu.nordtal.s2.steward.worker.backup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real directories, a real {@code tar}, a real {@code zstd} - and an extraction.
 *
 * <p><b>Nothing here is a stub.</b> An archive that no test ever unpacks is an archive nobody has
 * proved is an archive, which is one step short of run 23: a green suite, a file on disk, and no
 * evidence that anything could ever be restored from it. So the first test writes bytes, saves them
 * and reads the same bytes back out of a second directory.</p>
 *
 * <p>The clock is fixed in every test that looks at a file name, because the name is a promise
 * ({@code prune} sorts by it) and a promise measured against {@code Instant.now()} tests nothing.</p>
 */
class TarSnapshotsTest {

    /** 04:45 UTC, which is when the nightly run of §9a actually happens. */
    private static final Instant NIGHT = Instant.parse("2026-09-13T04:45:07Z");

    private static final String VOLUME = "nordtal-s2_mc-smp";

    @TempDir
    Path root;

    @Test
    @DisplayName("a saved volume comes back out byte for byte")
    void restores() throws IOException {
        final Path source = sourceDir(VOLUME);
        // Two files that a world actually has: incompressible binary (region data) and text
        // (server.properties), one of them nested, so the archive has to carry a directory too.
        final byte[] region = new byte[512 * 1024];
        new Random(23).nextBytes(region);
        Files.createDirectories(source.resolve("region"));
        Files.write(source.resolve("region/r.0.0.mca"), region);
        Files.writeString(source.resolve("server.properties"), "level-name=world\n");

        final SnapshotResult result = snapshots(NIGHT).save(VOLUME);

        assertTrue(result.ok(), result.message());
        assertTrue(result.bytes() > 0, "a backup of half a megabyte cannot be 0 bytes");
        assertNotNull(result.file());
        final Path archive = Path.of(result.file());
        assertEquals(result.bytes(), Files.size(archive), "the reported size is the file's own");
        assertTrue(Files.exists(archive));
        assertFalse(Files.exists(Path.of(result.file() + ".partial")),
                "a finished save leaves no partial behind");

        // The point of the whole class. Unpack it somewhere else and compare the bytes.
        final Path restored = Files.createDirectories(root.resolve("restored"));
        assertEquals(0, run("tar", "--zstd", "-xf", archive.toString(), "-C", restored.toString()),
                "the archive did not extract");
        assertArrayEquals(region, Files.readAllBytes(restored.resolve("region/r.0.0.mca")),
                "the region file did not survive the round trip");
        assertEquals("level-name=world\n",
                Files.readString(restored.resolve("server.properties")));
    }

    @Test
    @DisplayName("the name is the volume and a sortable UTC stamp")
    void names() throws IOException {
        Files.writeString(sourceDir(VOLUME).resolve("a.txt"), "a");

        final SnapshotResult earlier = snapshots(NIGHT).save(VOLUME);
        final SnapshotResult later = snapshots(NIGHT.plusSeconds(86_400)).save(VOLUME);

        assertEquals("nordtal-s2_mc-smp-20260913T044507Z.tar.zst",
                Path.of(earlier.file()).getFileName().toString());
        assertEquals("nordtal-s2_mc-smp-20260914T044507Z.tar.zst",
                Path.of(later.file()).getFileName().toString());
        // Fixed width and UTC, so sorting the text sorts the nights - which is what prune leans on
        // instead of an mtime that a copy off this host and back would have rewritten.
        assertTrue(Path.of(earlier.file()).getFileName().toString()
                        .compareTo(Path.of(later.file()).getFileName().toString()) < 0,
                "the names must sort chronologically as plain text");
    }

    @Test
    @DisplayName("an empty source directory fails and names the path - the A23 lesson")
    void emptyIsAFailure() throws IOException {
        final Path source = sourceDir(VOLUME);

        final SnapshotResult result = snapshots(NIGHT).save(VOLUME);

        assertFalse(result.ok(), "an empty world is a missing mount, not a backup");
        assertEquals(0, result.bytes());
        assertNull(result.file());
        assertTrue(result.message().contains(source.toString()),
                "the message must name the path that was empty, was: " + result.message());
        assertTrue(Files.notExists(outputRoot()) || archivesIn(outputRoot()).isEmpty(),
                "nothing may have been written");
    }

    @Test
    @DisplayName("a source directory that is not there fails and names the path")
    void missingIsAFailure() {
        final Path source = sourcesRoot().resolve(VOLUME);

        final SnapshotResult result = snapshots(NIGHT).save(VOLUME);

        assertFalse(result.ok());
        assertEquals(0, result.bytes());
        assertNull(result.file());
        assertTrue(result.message().contains(source.toString()),
                "the message must name the missing path, was: " + result.message());
    }

    @Test
    @DisplayName("garbage and a truncated archive are refused by the readback")
    void corruptionNeverBecomesAFinalFile() throws Exception {
        Files.writeString(sourceDir(VOLUME).resolve("a.txt"), "a".repeat(4096));
        final TarSnapshots snapshots = snapshots(NIGHT);
        final SnapshotResult good = snapshots.save(VOLUME);
        assertTrue(good.ok(), good.message());
        // The gate says yes to the real thing, which is the half of the claim that is easy to lose.
        assertNull(snapshots.unreadable(Path.of(good.file())));

        final Path garbage = outputRoot().resolve("garbage.tar.zst.partial");
        Files.writeString(garbage, "this is not a zstd frame and never was");
        assertNotNull(snapshots.unreadable(garbage), "garbage must not pass as an archive");

        // Truncated: a real archive cut in half. This is the one `zstd -t` alone would wave
        // through often enough to matter, and it is what a killed tar leaves on disk.
        final Path truncated = outputRoot().resolve("half.tar.zst.partial");
        final byte[] whole = Files.readAllBytes(Path.of(good.file()));
        Files.write(truncated, java.util.Arrays.copyOf(whole, whole.length / 2),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        assertNotNull(snapshots.unreadable(truncated), "half an archive must not pass as one");

        // And an archive of nothing - the 45-byte file that run 23 would have called a success.
        final Path empty = outputRoot().resolve("empty.tar.zst.partial");
        assertEquals(0, run("tar", "--zstd", "-cf", empty.toString(),
                "-C", Files.createDirectories(root.resolve("void")).toString(), "-T", "/dev/null"));
        assertNotNull(snapshots.unreadable(empty), "an empty archive must not pass as a backup");

        // None of the three was ever renamed: only the one real save is a .tar.zst in there.
        assertEquals(List.of(Path.of(good.file()).getFileName().toString()),
                archivesIn(outputRoot()));
    }

    @Test
    @DisplayName("prune keeps the newest per volume and never mixes them")
    void prunesPerVolume() throws IOException {
        // Four nights of smp, three of limbo, two of hunger-games. Written oldest first so that a
        // sweep that fell back on mtime would delete exactly the wrong ones.
        archive("nordtal-s2_mc-smp", "20260910T044500Z", "20260911T044500Z",
                "20260912T044500Z", "20260913T044500Z");
        archive("nordtal-s2_mc-limbo", "20260911T044500Z", "20260912T044500Z", "20260913T044500Z");
        archive("nordtal-s2_mc-hunger-games", "20260912T044500Z", "20260913T044500Z");
        // Not ours: an operator's own file in the same directory, which must survive untouched.
        Files.writeString(outputRoot().resolve("README.txt"), "restore instructions");

        final List<String> removed = snapshots(NIGHT).prune(2);

        assertEquals(List.of(
                        "nordtal-s2_mc-limbo-20260911T044500Z.tar.zst",
                        "nordtal-s2_mc-smp-20260910T044500Z.tar.zst",
                        "nordtal-s2_mc-smp-20260911T044500Z.tar.zst"),
                sorted(removed),
                "only the oldest of smp and limbo go; hunger-games is under the limit");
        assertEquals(List.of(
                        "nordtal-s2_mc-hunger-games-20260912T044500Z.tar.zst",
                        "nordtal-s2_mc-hunger-games-20260913T044500Z.tar.zst",
                        "nordtal-s2_mc-limbo-20260912T044500Z.tar.zst",
                        "nordtal-s2_mc-limbo-20260913T044500Z.tar.zst",
                        "nordtal-s2_mc-smp-20260912T044500Z.tar.zst",
                        "nordtal-s2_mc-smp-20260913T044500Z.tar.zst"),
                archivesIn(outputRoot()),
                "two of each must remain - six files, not two");
        assertTrue(Files.exists(outputRoot().resolve("README.txt")),
                "a file this class did not name must never be deleted");
    }

    @Test
    @DisplayName("a mark lands beside the archive with the reason in it, and leaves the archive alone")
    void marksBesideTheArchive() throws IOException {
        // Beside it rather than in it: the archive is a tar of a world directory and a restore
        // unpacks it, so a note added inside would end up in somebody's world. Beside it, the
        // warning is one line of an `ls` and the archive is byte for byte an ordinary one - which
        // matters, because an archive taken after an unverified stop is still very probably good.
        Files.writeString(sourceDir(VOLUME).resolve("a.txt"), "a".repeat(4096));
        final TarSnapshots snapshots = snapshots(NIGHT);
        final SnapshotResult result = snapshots.save(VOLUME);
        assertTrue(result.ok(), result.message());

        final String mark = snapshots.markUnverified(result.file(),
                "the end of smp could not be read back");

        assertEquals("nordtal-s2_mc-smp-20260913T044507Z.tar.zst.unverified", mark,
                "the name comes back so the report line can point at it by name");
        final Path beside = Path.of(result.file() + ".unverified");
        assertTrue(Files.exists(beside), "no mark was written at all");
        assertTrue(Files.readString(beside).contains("the end of smp could not be read back"),
                "the reason has to be in the file - a nameless warning tells nobody which server"
                        + " to go and look at: " + Files.readString(beside));
        assertEquals(result.bytes(), Files.size(Path.of(result.file())),
                "the archive itself is untouched; only the thing beside it is new");
        assertEquals(List.of("nordtal-s2_mc-smp-20260913T044507Z.tar.zst"),
                archivesIn(outputRoot()),
                "and the mark is not a second archive - restore.sh and prune both match by name");
    }

    @Test
    @DisplayName("a mark that cannot be written costs a log line, not the backup")
    void anUnwritableMarkIsNotAFailedBackup() {
        // The one thing worse than an unverified archive is no archive. UpdateRun reads this null
        // and says so in the report line instead of discarding a snapshot that succeeded.
        assertNull(snapshots(NIGHT).markUnverified(
                root.resolve("no/such/directory/x.tar.zst").toString(), "smp"));
    }

    @Test
    @DisplayName("a mark is swept with the archive it belongs to and never without it")
    void marksGoWithTheirArchive() throws IOException {
        // An orphaned warning is worse than none: it names a file that is no longer there, and a
        // directory of those is one nobody reads the next time it matters.
        archive("nordtal-s2_mc-smp", "20260910T044500Z", "20260911T044500Z", "20260912T044500Z");
        mark("nordtal-s2_mc-smp-20260910T044500Z.tar.zst");
        mark("nordtal-s2_mc-smp-20260912T044500Z.tar.zst");

        final List<String> removed = snapshots(NIGHT).prune(2);

        assertEquals(List.of("nordtal-s2_mc-smp-20260910T044500Z.tar.zst"), removed,
                "the sweep's list is what the run's report prints, and a mark listed there would"
                        + " read as a lost backup");
        assertFalse(Files.exists(outputRoot()
                        .resolve("nordtal-s2_mc-smp-20260910T044500Z.tar.zst.unverified")),
                "the mark of a deleted archive is a warning about a file that is gone");
        assertTrue(Files.exists(outputRoot()
                        .resolve("nordtal-s2_mc-smp-20260912T044500Z.tar.zst.unverified")),
                "and the mark of an archive that is still there has to survive, or the one archive"
                        + " somebody must not trust silently becomes indistinguishable");
        assertEquals(List.of("nordtal-s2_mc-smp-20260911T044500Z.tar.zst",
                        "nordtal-s2_mc-smp-20260912T044500Z.tar.zst"),
                archivesIn(outputRoot()),
                "and a mark is never counted against keep as though it were an archive of its own");
    }

    @Test
    @DisplayName("prune sweeps a day-old partial but leaves a fresh one alone")
    void prunesStalePartials() throws IOException {
        Files.createDirectories(outputRoot());
        final Path stale = outputRoot().resolve(VOLUME + "-20260911T044500Z.tar.zst.partial");
        final Path fresh = outputRoot().resolve(VOLUME + "-20260913T044000Z.tar.zst.partial");
        Files.writeString(stale, "debris from a killed run");
        Files.writeString(fresh, "a save that may be running right now");

        final List<String> removed = snapshots(NIGHT).prune(7);

        assertEquals(List.of(stale.getFileName().toString()), removed);
        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(fresh), "a partial from five minutes ago may be a running save");
    }

    @Test
    @DisplayName("a stamp that is not a date stops that one file, not the whole sweep")
    void animpossibleStampIsLeftAlone() throws IOException {
        // \d{8}T\d{6}Z accepts this and LocalDateTime.parse refuses it. Thrown, it ended prune
        // before a single archive was deleted: the backup volume then fills up over weeks with one
        // stack trace to show for it, on runs that otherwise report success.
        archive("nordtal-s2_mc-smp", "20260910T044500Z", "20260911T044500Z", "20260913T044500Z");
        final Path impossible = outputRoot().resolve(VOLUME + "-99999999T999999Z.tar.zst.partial");
        Files.writeString(impossible, "whatever this is");

        final List<String> removed = snapshots(NIGHT).prune(1);

        assertEquals(List.of("nordtal-s2_mc-smp-20260910T044500Z.tar.zst",
                        "nordtal-s2_mc-smp-20260911T044500Z.tar.zst"),
                sorted(removed), "the sweep has to finish the job it was there to do");
        assertTrue(Files.exists(impossible),
                "and leave the file it cannot date where it is, for a person to look at");
    }

    @Test
    @DisplayName("the wall belongs to the pipeline, not to each stage of it")
    void oneWallForTheWholePipeline() throws Exception {
        // Three stages ending 0.8 s, 1.6 s and 2.4 s from now, against a wall of one second. Each of
        // them fits in a fresh second measured from the end of the one before, so a per-stage wall
        // waits the whole 2.4 s and reports a pipeline that kept to its one-second limit. That is
        // 2.4 s of Minecraft servers held down by a config that said one, and it grows with the
        // number of stages.
        final List<Process> sleeping = sleepers(0.8, 1.6, 2.4);
        try {
            assertTrue(TarSnapshots.awaitAll(sleeping, Duration.ofSeconds(1)).isEmpty(),
                    "one second is the pipeline's whole allowance, not each stage's");
            // destroyForcibly is a signal, not a funeral, so this waits for the process to be gone
            // rather than asking a microsecond after asking for it.
            assertTrue(sleeping.getLast().waitFor(10, java.util.concurrent.TimeUnit.SECONDS),
                    "everything goes, not just the stage that was still running");
        } finally {
            sleeping.forEach(Process::destroyForcibly);
        }
    }

    @Test
    @DisplayName("and a pipeline that finishes inside it comes back with every exit code")
    void insideTheWallEveryStageIsReported() throws Exception {
        final List<Process> sleeping = sleepers(0.2, 0.4, 0.6);
        try {
            assertEquals(List.of(0, 0, 0),
                    TarSnapshots.awaitAll(sleeping, Duration.ofSeconds(20)).orElseThrow());
        } finally {
            sleeping.forEach(Process::destroyForcibly);
        }
    }

    /** Stages that do nothing but end at a known moment, started together as one pipeline. */
    private static List<Process> sleepers(final double... seconds) throws IOException {
        final List<ProcessBuilder> builders = new ArrayList<>();
        for (final double duration : seconds) {
            builders.add(new ProcessBuilder("sleep", String.valueOf(duration))
                    .redirectError(ProcessBuilder.Redirect.DISCARD));
        }
        return ProcessBuilder.startPipeline(builders);
    }

    @Test
    @DisplayName("prune(0) is refused rather than obeyed")
    void refusesZero() {
        assertThrows(IllegalArgumentException.class, () -> snapshots(NIGHT).prune(0));
    }

    // -----------------------------------------------------------------------------------------

    private TarSnapshots snapshots(final Instant now) {
        return new TarSnapshots(sourcesRoot(), outputRoot(), Clock.fixed(now, ZoneOffset.UTC));
    }

    private Path sourcesRoot() {
        return root.resolve("backup-sources");
    }

    private Path outputRoot() {
        return root.resolve("backups");
    }

    private Path sourceDir(final String volume) throws IOException {
        return Files.createDirectories(sourcesRoot().resolve(volume));
    }

    /** Writes archives that are real enough for prune, which reads names and never content. */
    private void archive(final String volume, final String... stamps) throws IOException {
        Files.createDirectories(outputRoot());
        for (final String stamp : stamps) {
            Files.writeString(outputRoot().resolve(volume + "-" + stamp + ".tar.zst"), stamp,
                    StandardCharsets.UTF_8);
        }
    }

    /** The sidecar a run writes when it could not read how the servers stopped. */
    private void mark(final String archive) throws IOException {
        Files.writeString(outputRoot().resolve(archive + ".unverified"),
                "the end of smp could not be read back\n", StandardCharsets.UTF_8);
    }

    private List<String> archivesIn(final Path directory) throws IOException {
        try (var listing = Files.list(directory)) {
            return listing.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".tar.zst"))
                    .sorted()
                    .toList();
        }
    }

    private static List<String> sorted(final List<String> names) {
        return names.stream().sorted().toList();
    }

    private static int run(final String... command) throws IOException {
        try {
            return new ProcessBuilder(command).inheritIO().start().waitFor();
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }
}
