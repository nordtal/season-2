package eu.nordtal.season.stewardagent.backup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.internalapi.agent.Retention;
import eu.nordtal.season.internalapi.agent.SnapshotResult;
import eu.nordtal.season.stewardagent.run.Snapshots;
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
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real directories, a real {@code tar} and a real {@code zstd}, checked by extracting the archive again.
 *
 * The clock is fixed wherever a file name is checked, because {@code prune} sorts by that name.
 */
class TarSnapshotsTest {

    /** 04:45 UTC, when the nightly run happens. */
    private static final Instant NIGHT = Instant.parse("2026-09-13T04:45:07Z");

    private static final String VOLUME = "nordtal-s2_mc-smp";

    private static final Set<String> IN_BACKUP = Set.of(VOLUME, "nordtal-s2_mc-limbo", "nordtal-s2_mc-hunger-games");

    /** How long one save may take, as {@code backup.patience-minutes} says by default. */
    private static final Duration WALL = Duration.ofMinutes(30);

    @TempDir
    Path root;

    @Test
    void aSavedVolumeComesBackOutByteForByte() throws IOException {
        final Path source = sourceDir(VOLUME);
        // Two files a world has: incompressible binary data and text, one nested, so a directory is needed too.
        final byte[] region = new byte[512 * 1024];
        new Random(23).nextBytes(region);
        Files.createDirectories(source.resolve("region"));
        Files.write(source.resolve("region/r.0.0.mca"), region);
        Files.writeString(source.resolve("server.properties"), "level-name=world\n");

        final SnapshotResult result = snapshots(NIGHT).save(VOLUME, WALL);

        assertTrue(result.ok(), result.message());
        assertTrue(result.bytes() > 0, "a backup of half a megabyte cannot be 0 bytes");
        assertNotNull(result.file());
        final Path archive = Path.of(result.file());
        assertEquals(result.bytes(), Files.size(archive), "the reported size is the file's own");
        assertTrue(Files.exists(archive));
        assertFalse(Files.exists(Path.of(result.file() + ".partial")), "a finished save leaves no partial behind");

        // Unpack it somewhere else and compare the bytes.
        final Path restored = Files.createDirectories(root.resolve("restored"));
        assertEquals(
                0,
                run("tar", "--zstd", "-xf", archive.toString(), "-C", restored.toString()),
                "the archive did not extract");
        assertArrayEquals(
                region,
                Files.readAllBytes(restored.resolve("region/r.0.0.mca")),
                "the region file did not survive the round trip");
        assertEquals("level-name=world\n", Files.readString(restored.resolve("server.properties")));
    }

    @Test
    void aRestoredVolumeHoldsTheArchiveAgainAndNothingThatCameAfterIt() throws IOException {
        final Path source = sourceDir(VOLUME);
        Files.createDirectories(source.resolve("region"));
        Files.writeString(source.resolve("region/r.0.0.mca"), "before");
        final SnapshotResult saved = snapshots(NIGHT).save(VOLUME, WALL);
        Files.writeString(source.resolve("region/r.0.0.mca"), "after");
        Files.writeString(source.resolve("griefed.txt"), "came later");

        final SnapshotResult restored = snapshots(NIGHT.plusSeconds(60))
                .restore(Path.of(saved.file()).getFileName().toString(), WALL);

        assertTrue(restored.ok(), restored.message());
        assertEquals("before", Files.readString(source.resolve("region/r.0.0.mca")));
        assertFalse(Files.exists(source.resolve("griefed.txt")), "a restore is the archive, not the archive added");
    }

    @Test
    void aRestoreOfAnArchiveThatDoesNotReadThroughLeavesTheVolumeAlone() throws IOException {
        Files.writeString(sourceDir(VOLUME).resolve("level.dat"), "live");
        final String broken = VOLUME + "-20260912T000000Z.tar.zst";
        Files.createDirectories(outputRoot());
        Files.writeString(outputRoot().resolve(broken), "not zstd");

        final SnapshotResult restored = snapshots(NIGHT).restore(broken, WALL);

        assertFalse(restored.ok(), restored.message());
        assertEquals("live", Files.readString(sourceDir(VOLUME).resolve("level.dat")));
    }

    @Test
    void onlyAFinishedArchiveDirectlyInTheBackupsIsAnyRestoresBusiness() throws IOException {
        Files.createDirectories(outputRoot().resolve("sub"));
        Files.writeString(outputRoot().resolve("sub/" + VOLUME + "-20260912T000000Z.tar.zst"), "x");
        Files.writeString(outputRoot().resolve("nordtal-20260912T000000Z.dump"), "x");

        assertEquals(Optional.empty(), snapshots(NIGHT).seriesOf("sub/" + VOLUME + "-20260912T000000Z.tar.zst"));
        assertEquals(Optional.empty(), snapshots(NIGHT).seriesOf("../" + VOLUME + "-20260912T000000Z.tar.zst"));
        assertEquals(Optional.empty(), snapshots(NIGHT).seriesOf(VOLUME + "-20260913T000000Z.tar.zst"));
        assertEquals(Optional.of(Snapshots.DATABASE), snapshots(NIGHT).seriesOf("nordtal-20260912T000000Z.dump"));
        assertFalse(
                snapshots(NIGHT).restore("nordtal-20260912T000000Z.dump", WALL).ok(), "a dump is no volume");
    }

    @Test
    void theNameIsTheVolumeAndASortableUtcStamp() throws IOException {
        Files.writeString(sourceDir(VOLUME).resolve("a.txt"), "a");

        final SnapshotResult earlier = snapshots(NIGHT).save(VOLUME, WALL);
        final SnapshotResult later = snapshots(NIGHT.plusSeconds(86_400)).save(VOLUME, WALL);

        assertEquals(
                "nordtal-s2_mc-smp-20260913T044507Z.tar.zst",
                Path.of(earlier.file()).getFileName().toString());
        assertEquals(
                "nordtal-s2_mc-smp-20260914T044507Z.tar.zst",
                Path.of(later.file()).getFileName().toString());
        // Fixed width and UTC, so sorting the text sorts the nights, which is what prune leans on instead of mtime.
        assertTrue(
                Path.of(earlier.file())
                                .getFileName()
                                .toString()
                                .compareTo(Path.of(later.file()).getFileName().toString())
                        < 0,
                "the names must sort chronologically as plain text");
    }

    @Test
    void isFinishedArchiveAcceptsOnlyAFinishedArchiveOrDump() {
        assertTrue(TarSnapshots.isFinishedArchive("nordtal-s2_mc-smp-20260913T044507Z.tar.zst"));
        assertTrue(TarSnapshots.isFinishedArchive("nordtal-20260913T044507Z.dump"));
        assertFalse(
                TarSnapshots.isFinishedArchive("nordtal-s2_mc-smp-20260913T044507Z.tar.zst.partial"),
                "a partial archive is not a backup yet");
        assertFalse(
                TarSnapshots.isFinishedArchive("nordtal-20260913T044507Z.dump.partial"),
                "a partial dump is not a backup yet");
        assertFalse(TarSnapshots.isFinishedArchive("not-a-backup.txt"));
        // A dot matches a slash too, so a traversal segment matches here; the caller must confirm the resolved path.
        assertTrue(
                TarSnapshots.isFinishedArchive("../../etc/passwd-20260913T044507Z.tar.zst"),
                "the naming pattern alone cannot see a traversal segment - a resolved-path check is required as well");
    }

    @Test
    void anEmptySourceDirectoryFailsAndNamesThePath() throws IOException {
        final Path source = sourceDir(VOLUME);

        final SnapshotResult result = snapshots(NIGHT).save(VOLUME, WALL);

        assertFalse(result.ok(), "an empty world is a missing mount, not a backup");
        assertEquals(0, result.bytes());
        assertNull(result.file());
        assertTrue(
                result.message().contains(source.toString()),
                "the message must name the path that was empty, was: " + result.message());
        assertTrue(
                Files.notExists(outputRoot()) || archivesIn(outputRoot()).isEmpty(), "nothing may have been written");
    }

    @Test
    void aSourceDirectoryThatIsNotThereFailsAndNamesThePath() {
        final Path source = sourcesRoot().resolve(VOLUME);

        final SnapshotResult result = snapshots(NIGHT).save(VOLUME, WALL);

        assertFalse(result.ok());
        assertEquals(0, result.bytes());
        assertNull(result.file());
        assertTrue(
                result.message().contains(source.toString()),
                "the message must name the missing path, was: " + result.message());
    }

    @Test
    void garbageAndATruncatedArchiveAreRefusedByTheReadback() throws Exception {
        Files.writeString(sourceDir(VOLUME).resolve("a.txt"), "a".repeat(4096));
        final TarSnapshots snapshots = snapshots(NIGHT);
        final SnapshotResult good = snapshots.save(VOLUME, WALL);
        assertTrue(good.ok(), good.message());
        // The gate says yes to the real thing, which is the half of the claim that is easy to lose.
        assertNull(snapshots.unreadable(Path.of(good.file()), WALL));

        final Path garbage = outputRoot().resolve("garbage.tar.zst.partial");
        Files.writeString(garbage, "this is not a zstd frame and never was");
        assertNotNull(snapshots.unreadable(garbage, WALL), "garbage must not pass as an archive");

        // A real archive cut in half: what zstd -t alone would often wave through, and what a killed tar leaves.
        final Path truncated = outputRoot().resolve("half.tar.zst.partial");
        final byte[] whole = Files.readAllBytes(Path.of(good.file()));
        Files.write(
                truncated,
                java.util.Arrays.copyOf(whole, whole.length / 2),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        assertNotNull(snapshots.unreadable(truncated, WALL), "half an archive must not pass as one");

        // And an archive of nothing, the 45-byte file a broken save would call a success.
        final Path empty = outputRoot().resolve("empty.tar.zst.partial");
        assertEquals(
                0,
                run(
                        "tar",
                        "--zstd",
                        "-cf",
                        empty.toString(),
                        "-C",
                        Files.createDirectories(root.resolve("void")).toString(),
                        "-T",
                        "/dev/null"));
        assertNotNull(snapshots.unreadable(empty, WALL), "an empty archive must not pass as a backup");

        // None of the three was ever renamed: only the one real save is a .tar.zst in there.
        assertEquals(List.of(Path.of(good.file()).getFileName().toString()), archivesIn(outputRoot()));
    }

    @Test
    void pruneKeepsTheNewestPerVolumeAndNeverMixesThem() throws IOException {
        // Four nights of smp, three of limbo, two of hunger-games, oldest first so mtime fallback picks the wrong ones.
        archive("nordtal-s2_mc-smp", "20260910T044500Z", "20260911T044500Z", "20260912T044500Z", "20260913T044500Z");
        archive("nordtal-s2_mc-limbo", "20260911T044500Z", "20260912T044500Z", "20260913T044500Z");
        archive("nordtal-s2_mc-hunger-games", "20260912T044500Z", "20260913T044500Z");
        // Not ours: an operator's own file in the same directory, which must survive untouched.
        Files.writeString(outputRoot().resolve("README.txt"), "restore instructions");

        final List<String> removed =
                snapshots(NIGHT).prune(days(2), IN_BACKUP, 100).expired();

        assertEquals(
                List.of(
                        "nordtal-s2_mc-limbo-20260911T044500Z.tar.zst",
                        "nordtal-s2_mc-smp-20260910T044500Z.tar.zst",
                        "nordtal-s2_mc-smp-20260911T044500Z.tar.zst"),
                sorted(removed),
                "only the oldest of smp and limbo go; hunger-games is under the limit");
        assertEquals(
                List.of(
                        "nordtal-s2_mc-hunger-games-20260912T044500Z.tar.zst",
                        "nordtal-s2_mc-hunger-games-20260913T044500Z.tar.zst",
                        "nordtal-s2_mc-limbo-20260912T044500Z.tar.zst",
                        "nordtal-s2_mc-limbo-20260913T044500Z.tar.zst",
                        "nordtal-s2_mc-smp-20260912T044500Z.tar.zst",
                        "nordtal-s2_mc-smp-20260913T044500Z.tar.zst"),
                archivesIn(outputRoot()),
                "two of each must remain - six files, not two");
        assertTrue(
                Files.exists(outputRoot().resolve("README.txt")),
                "a file this class did not name must never be deleted");
    }

    @Test
    void aVolumeOutsideTheBackupAgesOutInsteadOfKeepingItsNewestArchives() throws IOException {
        archive("nordtal-s2_mc-smp", "20260912T044500Z", "20260913T044500Z");
        archive("nordtal-s2_mc-proxy", "20260901T044500Z", "20260902T044500Z");
        dump("20260901T044500Z");

        final List<String> removed =
                snapshots(NIGHT).prune(days(7), IN_BACKUP, 100).expired();

        assertEquals(
                List.of("nordtal-20260901T044500Z.dump"),
                dumpsIn(outputRoot()),
                "the dump is always in the backup, so its newest is held however old");
        assertEquals(
                List.of("nordtal-s2_mc-proxy-20260901T044500Z.tar.zst", "nordtal-s2_mc-proxy-20260902T044500Z.tar.zst"),
                sorted(removed).stream().filter(name -> name.contains("proxy")).toList(),
                "a week back on the calendar holds neither, and nothing keeps the newest for being the newest");
        assertTrue(archivesIn(outputRoot()).contains("nordtal-s2_mc-smp-20260913T044500Z.tar.zst"));
    }

    @Test
    void aDayThatHasSettledKeepsItsLastRunOnTheDiskAndNotOnlyOnPaper() throws IOException {
        // Three runs on one day and two on the next, all inside the grace window measured from the newest one.
        archive(
                "nordtal-s2_mc-smp",
                "20260910T044500Z",
                "20260910T113000Z",
                "20260910T211500Z",
                "20260912T044500Z",
                "20260912T190000Z");

        final List<String> removed = snapshots(NIGHT)
                .prune(new Retention(30, 0, 0, 2), IN_BACKUP, 100)
                .expired();

        assertEquals(
                List.of("nordtal-s2_mc-smp-20260910T044500Z.tar.zst", "nordtal-s2_mc-smp-20260910T113000Z.tar.zst"),
                sorted(removed),
                "the 10th has settled and keeps its last run; the 12th is inside the grace and"
                        + " keeps both, which is the whole point of the grace");
        assertEquals(
                List.of(
                        "nordtal-s2_mc-smp-20260910T211500Z.tar.zst",
                        "nordtal-s2_mc-smp-20260912T044500Z.tar.zst",
                        "nordtal-s2_mc-smp-20260912T190000Z.tar.zst"),
                archivesIn(outputRoot()));
    }

    @Test
    void aMarkLandsBesideTheArchiveWithTheReasonInItAndLeavesTheArchiveAlone() throws IOException {
        // Beside it, not inside it: a tar of a world directory unpacks whole, so a note inside would land in the world.
        Files.writeString(sourceDir(VOLUME).resolve("a.txt"), "a".repeat(4096));
        final TarSnapshots snapshots = snapshots(NIGHT);
        final SnapshotResult result = snapshots.save(VOLUME, WALL);
        assertTrue(result.ok(), result.message());

        final String mark = snapshots.markUnverified(result.file(), "the end of smp could not be read back");

        assertEquals(
                "nordtal-s2_mc-smp-20260913T044507Z.tar.zst.unverified",
                mark,
                "the name comes back so the report line can point at it by name");
        final Path beside = Path.of(result.file() + ".unverified");
        assertTrue(Files.exists(beside), "no mark was written at all");
        assertTrue(
                Files.readString(beside).contains("the end of smp could not be read back"),
                "the reason has to be in the file - a nameless warning tells nobody which server"
                        + " to go and look at: " + Files.readString(beside));
        assertEquals(
                result.bytes(),
                Files.size(Path.of(result.file())),
                "the archive itself is untouched; only the thing beside it is new");
        assertEquals(
                List.of("nordtal-s2_mc-smp-20260913T044507Z.tar.zst"),
                archivesIn(outputRoot()),
                "and the mark is not a second archive - restore.sh and prune both match by name");
    }

    @Test
    void aMarkThatCannotBeWrittenCostsALogLineNotTheBackup() {
        // No archive is worse than an unverified one; UpdateRun reads null, rather than discarding a good snapshot.
        assertNull(snapshots(NIGHT)
                .markUnverified(root.resolve("no/such/directory/x.tar.zst").toString(), "smp"));
    }

    @Test
    void aMarkIsSweptWithTheArchiveItBelongsToAndNeverWithoutIt() throws IOException {
        // An orphaned warning names a file that is no longer there, and a directory full of those goes unread.
        archive("nordtal-s2_mc-smp", "20260910T044500Z", "20260911T044500Z", "20260912T044500Z");
        mark("nordtal-s2_mc-smp-20260910T044500Z.tar.zst");
        mark("nordtal-s2_mc-smp-20260912T044500Z.tar.zst");

        final List<String> removed =
                snapshots(NIGHT).prune(days(2), IN_BACKUP, 100).expired();

        assertEquals(
                List.of("nordtal-s2_mc-smp-20260910T044500Z.tar.zst"),
                removed,
                "the sweep's list is what the run's report prints, and a mark listed there would"
                        + " read as a lost backup");
        assertFalse(
                Files.exists(outputRoot().resolve("nordtal-s2_mc-smp-20260910T044500Z.tar.zst.unverified")),
                "the mark of a deleted archive is a warning about a file that is gone");
        assertTrue(
                Files.exists(outputRoot().resolve("nordtal-s2_mc-smp-20260912T044500Z.tar.zst.unverified")),
                "and the mark of an archive that is still there has to survive, or the one archive"
                        + " somebody must not trust silently becomes indistinguishable");
        assertEquals(
                List.of("nordtal-s2_mc-smp-20260911T044500Z.tar.zst", "nordtal-s2_mc-smp-20260912T044500Z.tar.zst"),
                archivesIn(outputRoot()),
                "and a mark is never counted against keep as though it were an archive of its own");
    }

    @Test
    void pastTheDiskBudgetTheOldestArchivesOfTheLargestSeriesGoAndTheirMarksWithThem() throws IOException {
        sized("nordtal-s2_mc-smp-20260911T044500Z.tar.zst", 400);
        sized("nordtal-s2_mc-smp-20260912T044500Z.tar.zst", 400);
        sized("nordtal-s2_mc-smp-20260913T044500Z.tar.zst", 400);
        mark("nordtal-s2_mc-smp-20260913T044500Z.tar.zst");
        sized("nordtal-s2_mc-limbo-20260913T044500Z.tar.zst", 100);
        dump("20260913T044500Z");

        // A disk of 1000 bytes and a budget of 50 percent: 1300 bytes have to come down to 500.
        final Snapshots.Pruned pruned = new TarSnapshots(
                        sourcesRoot(), outputRoot(), Clock.fixed(NIGHT, ZoneOffset.UTC), disk(1000, 0))
                .prune(days(30), IN_BACKUP, 50);

        assertEquals(List.of(), pruned.expired(), "the policy keeps all of them");
        assertEquals(
                List.of("nordtal-s2_mc-smp-20260911T044500Z.tar.zst", "nordtal-s2_mc-smp-20260913T044500Z.tar.zst"),
                pruned.overBudget(),
                "the unverified newest goes before the verified one behind it");
        assertEquals(
                List.of("nordtal-s2_mc-limbo-20260913T044500Z.tar.zst", "nordtal-s2_mc-smp-20260912T044500Z.tar.zst"),
                archivesIn(outputRoot()));
        assertFalse(Files.exists(outputRoot().resolve("nordtal-s2_mc-smp-20260913T044500Z.tar.zst.unverified")));
    }

    @Test
    void aBackupIsSizedByEachSeriesNewestArchiveAndRefusedWhenItWouldEatTheFreeShare() throws IOException {
        sized("nordtal-s2_mc-smp-20260912T044500Z.tar.zst", 900);
        sized("nordtal-s2_mc-smp-20260913T044500Z.tar.zst", 400);
        Files.writeString(outputRoot().resolve("nordtal-20260913T044500Z.dump"), "x".repeat(50));
        Files.createDirectories(sourcesRoot().resolve("nordtal-s2_mc-limbo"));
        Files.writeString(sourcesRoot().resolve("nordtal-s2_mc-limbo").resolve("level.dat"), "a new volume");

        final Snapshots.Room tight = new TarSnapshots(
                        sourcesRoot(), outputRoot(), Clock.fixed(NIGHT, ZoneOffset.UTC), disk(10_000, 1_400))
                .room(List.of(VOLUME, "nordtal-s2_mc-limbo"), 10);

        // smp's newest, the dump's newest, and limbo, which has no archive yet, by what du says it takes.
        assertTrue(tight.expectedBytes() >= 450, String.valueOf(tight));
        assertEquals(1_000, tight.reserveBytes());
        assertFalse(tight.fits(), "1400 free, about 450 written, 1000 kept free: refused");

        final Snapshots.Room roomy = new TarSnapshots(
                        sourcesRoot(), outputRoot(), Clock.fixed(NIGHT, ZoneOffset.UTC), disk(10_000, 5_000))
                .room(List.of(VOLUME), 10);
        assertEquals(450, roomy.expectedBytes());
        assertTrue(roomy.fits());
    }

    @Test
    void pruneKeepsTheNewestDatabaseDumpsTooAndCountsThemApartFromTheVolumes() throws IOException {
        // Neither ARCHIVE nor PARTIAL_ARCHIVE matches .dump, so the sweep must walk past every database dump.
        archive("nordtal-s2_mc-smp", "20260910T044500Z", "20260911T044500Z", "20260912T044500Z");
        dump("20260910T044500Z", "20260911T044500Z", "20260912T044500Z", "20260913T044500Z");

        final List<String> removed =
                snapshots(NIGHT).prune(days(2), IN_BACKUP, 100).expired();

        assertEquals(
                List.of(
                        "nordtal-20260910T044500Z.dump",
                        "nordtal-20260911T044500Z.dump",
                        "nordtal-s2_mc-smp-20260910T044500Z.tar.zst"),
                sorted(removed),
                "two of each series survive - the dumps are not counted against the volume's two");
        assertEquals(
                List.of("nordtal-20260912T044500Z.dump", "nordtal-20260913T044500Z.dump"),
                dumpsIn(outputRoot()),
                "the two newest dumps remain");
    }

    @Test
    void pruneSweepsADayOldPartialDumpAndLeavesAFreshOneAlone() throws IOException {
        // A dump killed mid-write leaves the debris the tar side sweeps, under the same grace a live dump must survive.
        Files.createDirectories(outputRoot());
        Files.writeString(outputRoot().resolve("nordtal-20260912T044500Z.dump.partial"), "old");
        Files.writeString(outputRoot().resolve("nordtal-20260913T044500Z.dump.partial"), "running");

        final List<String> removed =
                snapshots(NIGHT).prune(days(7), IN_BACKUP, 100).expired();

        assertEquals(
                List.of("nordtal-20260912T044500Z.dump.partial"),
                sorted(removed),
                "a day old is debris; the one from tonight is a dump in progress");
    }

    @Test
    void pruneSweepsADayOldPartialButLeavesAFreshOneAlone() throws IOException {
        Files.createDirectories(outputRoot());
        final Path stale = outputRoot().resolve(VOLUME + "-20260911T044500Z.tar.zst.partial");
        final Path fresh = outputRoot().resolve(VOLUME + "-20260913T044000Z.tar.zst.partial");
        Files.writeString(stale, "debris from a killed run");
        Files.writeString(fresh, "a save that may be running right now");

        final List<String> removed =
                snapshots(NIGHT).prune(days(7), IN_BACKUP, 100).expired();

        assertEquals(List.of(stale.getFileName().toString()), removed);
        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(fresh), "a partial from five minutes ago may be a running save");
    }

    @Test
    void aStampThatIsNotADateStopsThatOneFileNotTheWholeSweep() throws IOException {
        // A pattern accepting a timestamp LocalDateTime.parse refuses must not end prune before an archive is deleted.
        archive("nordtal-s2_mc-smp", "20260910T044500Z", "20260911T044500Z", "20260913T044500Z");
        final Path impossible = outputRoot().resolve(VOLUME + "-99999999T999999Z.tar.zst.partial");
        Files.writeString(impossible, "whatever this is");

        final List<String> removed =
                snapshots(NIGHT).prune(days(1), IN_BACKUP, 100).expired();

        assertEquals(
                List.of("nordtal-s2_mc-smp-20260910T044500Z.tar.zst", "nordtal-s2_mc-smp-20260911T044500Z.tar.zst"),
                sorted(removed),
                "the sweep has to finish the job it was there to do");
        assertTrue(Files.exists(impossible), "and leave the file it cannot date where it is, for a person to look at");
    }

    @Test
    void theWallBelongsToThePipelineNotToEachStageOfIt() throws Exception {
        // Each stage fits in a fresh second measured from the one before it, so a per-stage wall keeps its own limit.
        final List<Process> sleeping = sleepers(0.8, 1.6, 2.4);
        try {
            assertTrue(
                    Pipeline.awaitAll(sleeping, Duration.ofSeconds(1)).isEmpty(),
                    "one second is the pipeline's whole allowance, not each stage's");
            // destroyForcibly is a signal, not a funeral; this waits for the process to actually be gone.
            assertTrue(
                    sleeping.getLast().waitFor(10, java.util.concurrent.TimeUnit.SECONDS),
                    "everything goes, not just the stage that was still running");
        } finally {
            sleeping.forEach(Process::destroyForcibly);
        }
    }

    @Test
    void andAPipelineThatFinishesInsideItComesBackWithEveryExitCode() throws Exception {
        final List<Process> sleeping = sleepers(0.2, 0.4, 0.6);
        try {
            assertEquals(
                    List.of(0, 0, 0),
                    Pipeline.awaitAll(sleeping, Duration.ofSeconds(20)).orElseThrow());
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

    /** The flat retention these tests were written against: N days, nothing weekly or monthly, no grace. */
    private static Retention days(final int daily) {
        return new Retention(daily, 0, 0, 0);
    }

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

    /** An archive of exactly {@code bytes}, for the budget, which weighs files and never reads them. */
    private void sized(final String name, final int bytes) throws IOException {
        Files.createDirectories(outputRoot());
        Files.write(outputRoot().resolve(name), new byte[bytes]);
    }

    /** A filesystem of {@code total} bytes with {@code usable} of them still writable. */
    private static TarSnapshots.Space disk(final long total, final long usable) {
        return new TarSnapshots.Space() {
            @Override
            public long totalBytes() {
                return total;
            }

            @Override
            public long usableBytes() {
                return usable;
            }
        };
    }

    /** Writes archives that are real enough for prune, which reads names and never content. */
    private void archive(final String volume, final String... stamps) throws IOException {
        Files.createDirectories(outputRoot());
        for (final String stamp : stamps) {
            Files.writeString(outputRoot().resolve(volume + "-" + stamp + ".tar.zst"), stamp, StandardCharsets.UTF_8);
        }
    }

    /** Dumps that are real enough for prune, which reads names and never content. */
    private void dump(final String... stamps) throws IOException {
        Files.createDirectories(outputRoot());
        for (final String stamp : stamps) {
            Files.writeString(outputRoot().resolve("nordtal-" + stamp + ".dump"), stamp, StandardCharsets.UTF_8);
        }
    }

    private List<String> dumpsIn(final Path directory) throws IOException {
        try (var listing = Files.list(directory)) {
            return listing.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".dump"))
                    .sorted()
                    .toList();
        }
    }

    /** The sidecar a run writes when it could not read how the servers stopped. */
    private void mark(final String archive) throws IOException {
        Files.writeString(
                outputRoot().resolve(archive + ".unverified"),
                "the end of smp could not be read back\n",
                StandardCharsets.UTF_8);
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
