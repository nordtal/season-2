package eu.nordtal.s2.steward.worker.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The parser, fed a {@code /proc} that is a directory of captured text.
 *
 * Every fixture below is captured from a real host - {@code cat /proc/loadavg}, {@code head -20 /proc/meminfo} and
 * two {@code head -7 /proc/stat} two seconds apart - and pasted in unedited except where a test says it edited one
 * line. That matters more than it looks: an invented
 * {@code /proc/meminfo} agrees with whatever the parser happens to do, and the two kinds of line this file actually
 * contains (a size with a {@code kB} suffix, a counter with no unit at all) are exactly the distinction a made-up
 * fixture would smooth over.
 */
class HostMetricsTest {

    /** {@code cat /proc/loadavg} on a real host. */
    private static final String LOADAVG = "0.46 0.42 0.36 1/912 2757165\n";

    /**
     * {@code head -7 /proc/stat}: the aggregate line and this host's six cores.
     *
     * The rest of the real file ({@code intr}, {@code ctxt}, {@code btime}, ...) is left off because nothing here
     * reads it, and the {@code intr} line alone is several kilobytes of zeroes.
     */
    private static final String STAT_FIRST = """
            cpu  2670895 785 1558887 65586703 309159 0 519579 86870 0 0
            cpu0 439987 169 262164 10970982 51279 0 157230 11847 0 0
            cpu1 447165 6 260053 10967785 53445 0 81501 11785 0 0
            cpu2 450905 2 260763 10961066 53101 0 41117 11867 0 0
            cpu3 437611 155 258744 10752334 48979 0 215660 27394 0 0
            cpu4 445485 449 258575 10970885 50670 0 13609 11887 0 0
            cpu5 449740 0 258585 10963649 51683 0 10459 12087 0 0
            """;

    /** The same file two seconds later. A mostly idle host, which is what this one was. */
    private static final String STAT_SECOND = """
            cpu  2670925 785 1558916 65587827 309160 0 519586 86871 0 0
            cpu0 439992 169 262171 10971169 51279 0 157230 11847 0 0
            cpu1 447169 6 260059 10967973 53445 0 81502 11785 0 0
            cpu2 450906 2 260767 10961256 53101 0 41118 11867 0 0
            cpu3 437613 155 258748 10752521 48979 0 215665 27394 0 0
            cpu4 445496 449 258581 10971067 50670 0 13609 11888 0 0
            cpu5 449745 0 258587 10963839 51683 0 10460 12087 0 0
            """;

    /** {@code head -20 /proc/meminfo} from a host with no swap, which says so in kB. */
    private static final String MEMINFO = """
            MemTotal:       16372536 kB
            MemFree:         1355252 kB
            MemAvailable:    5364344 kB
            Buffers:          172032 kB
            Cached:          3992224 kB
            SwapCached:            0 kB
            Active:          2151056 kB
            Inactive:       12378084 kB
            Active(anon):      11828 kB
            Inactive(anon): 10383788 kB
            Active(file):    2139228 kB
            Inactive(file):  1994296 kB
            Unevictable:       27716 kB
            Mlocked:           27716 kB
            SwapTotal:             0 kB
            SwapFree:              0 kB
            Dirty:               192 kB
            Writeback:             0 kB
            AnonPages:      10392856 kB
            Mapped:           550980 kB
            """;

    @TempDir
    Path proc;

    /** The filesystem the temporary directory is on - a real one, so the disk half is not faked. */
    @TempDir
    Path disk;

    @BeforeEach
    void writeTheCapturedProc() throws IOException {
        write("loadavg", LOADAVG);
        write("stat", STAT_FIRST);
        write("meminfo", MEMINFO);
    }

    private void write(final String name, final String content) throws IOException {
        Files.writeString(proc.resolve(name), content, StandardCharsets.UTF_8);
    }

    private HostMetrics metrics() {
        return new HostMetrics(proc, disk);
    }

    @Test
    void theCapturedProcParsesToTheNumbersUptimeAndFreePrintedBesideIt() throws IOException {
        final HostSnapshot snapshot = metrics().read();

        assertEquals(0.46, snapshot.load1());
        assertEquals(0.42, snapshot.load5());
        assertEquals(0.36, snapshot.load15());

        // Six cpuN lines, not seven: the aggregate "cpu " line is not a core.
        assertEquals(6, snapshot.cpus());

        // kB in the file is KiB. 16372536 * 1024 is what `free -b` printed: 16765476864.
        assertEquals(16_765_476_864L, snapshot.memoryTotalBytes());
        assertEquals(5_493_088_256L, snapshot.memoryAvailableBytes());
        assertEquals(1_387_778_048L, snapshot.memoryFreeBytes());

        // Available is nearly four times free, and that gap is why both are in the record, not just one.
        assertTrue(snapshot.memoryAvailableBytes() > snapshot.memoryFreeBytes());
    }

    @Test
    void noSwapIsZeroSwapAndThatIsNotAMissingValue() throws IOException {
        final HostSnapshot snapshot = metrics().read();

        assertEquals(0L, snapshot.swapTotalBytes());
        assertEquals(0L, snapshot.swapFreeBytes());
    }

    @Test
    void aKernelWithNoSwaptotalLineAtAllIsAlsoZeroSwapNotAnError() throws IOException {
        // CONFIG_SWAP=n prints no Swap* lines at all - the one field where a missing line gets a default, not an error.
        write(
                "meminfo",
                MEMINFO.lines().filter(line -> !line.startsWith("Swap")).reduce("", (a, b) -> a + b + "\n"));

        final HostSnapshot snapshot = metrics().read();

        assertEquals(0L, snapshot.swapTotalBytes());
        assertEquals(0L, snapshot.swapFreeBytes());
    }

    @Test
    void theFirstReadCannotKnowTheCpuPercentageAndSaysSoTheSecondCan() throws IOException {
        final HostMetrics metrics = metrics();

        // A first reading is a counter since boot, and there is nothing to subtract it from.
        assertFalse(
                metrics.read().cpuPercent().isPresent(),
                "the first read has no previous reading and must not invent 0.0");

        write("stat", STAT_SECOND);
        final HostSnapshot second = metrics.read();

        assertTrue(second.cpuPercent().isPresent(), "the second read has an interval to divide by");
        // By hand from the two captured lines: total moved 1192 jiffies, idle+iowait 1125 of them, so ~5.62%.
        assertEquals(5.620805369127517, second.cpuPercent().getAsDouble(), 1e-9);
    }

    @Test
    void twoReadsOfTheSameCountersAreNoIntervalAtAllSoStillNoPercentage() throws IOException {
        final HostMetrics metrics = metrics();
        metrics.read();

        // What a caller polling faster than the jiffy resolution sees: dividing by a zero interval is a NaN.
        assertFalse(metrics.read().cpuPercent().isPresent());
    }

    @Test
    void aMissingMemavailableThrowsNamingTheFieldRatherThanReportingZero() throws IOException {
        // MemAvailable has existed for a decade; its absence means this is not the file we think it is.
        write(
                "meminfo",
                MEMINFO.lines().filter(line -> !line.startsWith("MemAvailable")).reduce("", (a, b) -> a + b + "\n"));

        final IOException thrown =
                assertThrows(IOException.class, () -> metrics().read());

        assertTrue(thrown.getMessage().contains("MemAvailable"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("meminfo"), thrown.getMessage());
    }

    @Test
    void aUnitThatIsNotKbThrowsQuotingTheLineRatherThanBeingReadAsKb() throws IOException {
        // If the kernel ever prints a size in MB, reading it as kilobytes is wrong by 1024 and looks plausible.
        write("meminfo", MEMINFO.replace("MemTotal:       16372536 kB", "MemTotal:          15988 MB"));

        final IOException thrown =
                assertThrows(IOException.class, () -> metrics().read());

        assertTrue(thrown.getMessage().contains("15988 MB"), thrown.getMessage());
    }

    @Test
    void aMissingProcFileIsANosuchfileexceptionAndNotASnapshotOfZeroes() throws IOException {
        Files.delete(proc.resolve("stat"));

        assertThrows(NoSuchFileException.class, () -> metrics().read());
    }

    @Test
    void theDiskHalfIsTheFilestoresWithUsedMeasuredAgainstUnallocated() throws IOException {
        // Numbers move while the test runs on a live filesystem, so the snapshot must lie between two live reads.
        final FileStore before = Files.getFileStore(disk);
        final long usedBefore = before.getTotalSpace() - before.getUnallocatedSpace();
        final long freeBefore = before.getUsableSpace();

        final HostSnapshot snapshot = metrics().read();

        final FileStore after = Files.getFileStore(disk);
        final long usedAfter = after.getTotalSpace() - after.getUnallocatedSpace();
        final long freeAfter = after.getUsableSpace();

        // A filesystem's size does not move, so this one is a plain equality.
        assertEquals(before.getTotalSpace(), snapshot.diskTotalBytes());
        assertBetween(usedBefore, usedAfter, snapshot.diskUsedBytes(), "used");
        assertBetween(freeBefore, freeAfter, snapshot.diskFreeBytes(), "free");

        // used is total minus UNALLOCATED, free is USABLE; the gap between them is the root reserve, same as df.
        assertTrue(
                snapshot.diskUsedBytes() + snapshot.diskFreeBytes() <= snapshot.diskTotalBytes(),
                "used + free may fall short of total by the root reserve, but never exceed it");
    }

    private static void assertBetween(final long a, final long b, final long actual, final String what) {
        assertTrue(
                actual >= Math.min(a, b) && actual <= Math.max(a, b),
                what + " was " + actual + ", outside the [" + Math.min(a, b) + ", " + Math.max(a, b)
                        + "] the filesystem reported on either side of the reading");
    }
}
