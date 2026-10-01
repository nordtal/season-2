package eu.nordtal.s2.steward.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalDouble;
import java.util.regex.Pattern;

/**
 * Reads {@link HostSnapshot} out of {@code /proc} and one {@code statvfs}, which needs no privilege; Linux only.
 *
 * CPU is a delta against the previous reading, so {@link #read()} is synchronized and its interval is the caller's.
 */
public final class HostMetrics {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Where the kernel's own numbers are; the tests pass a captured copy. */
    private static final String PROC = "/proc";

    /** The filesystem measured by default; inside a container {@code /} reports the host's backing filesystem. */
    private static final String DISK = "/";

    private final Path procRoot;
    private final Path diskPath;

    /** The previous {@code /proc/stat} totals, or {@code -1} before the first reading. */
    private long previousTotalJiffies = -1;

    private long previousIdleJiffies = -1;

    /** Reads the real {@code /proc} and the filesystem holding {@code /}. */
    public HostMetrics() {
        this(Path.of(PROC), Path.of(DISK));
    }

    /**
     * Reads the given files instead of the real ones.
     *
     * @param procRoot a directory holding {@code loadavg}, {@code stat} and {@code meminfo} in the kernel's format
     * @param diskPath any path on the filesystem to measure
     */
    public HostMetrics(final Path procRoot, final Path diskPath) {
        this.procRoot = procRoot;
        this.diskPath = diskPath;
    }

    /**
     * One reading of everything.
     *
     * @return the snapshot, whose {@link HostSnapshot#cpuPercent()} is absent on the first call
     * @throws IOException if a file is missing, or holds a missing field or a unit other than {@code kB}
     */
    public synchronized HostSnapshot read() throws IOException {
        final Load load = readLoad();
        final Cpu cpu = readCpu();
        final Memory memory = readMemory();

        final OptionalDouble percent = percentSince(cpu);
        previousTotalJiffies = cpu.total();
        previousIdleJiffies = cpu.idle();

        // getUsableSpace(), not getUnallocatedSpace(): a non-root process cannot write into the root reserve.
        final FileStore store = Files.getFileStore(diskPath);
        final long diskTotal = store.getTotalSpace();
        final long diskUsed = diskTotal - store.getUnallocatedSpace();
        final long diskFree = store.getUsableSpace();

        return new HostSnapshot(
                load.one(),
                load.five(),
                load.fifteen(),
                cpu.cpus(),
                percent,
                memory.total(),
                memory.available(),
                memory.free(),
                memory.swapTotal(),
                memory.swapFree(),
                diskTotal,
                diskUsed,
                diskFree);
    }

    private record Load(double one, double five, double fifteen) {}

    /** Parses the three averages of one {@code loadavg} line. */
    private Load readLoad() throws IOException {
        final Path file = procRoot.resolve("loadavg");
        final String line = Files.readString(file, StandardCharsets.UTF_8).strip();
        final String[] fields = WHITESPACE.splitAsStream(line).toArray(String[]::new);
        if (fields.length < 3) {
            throw new IOException(file + " does not hold three load averages: '" + line + "'");
        }
        try {
            // Double.parseDouble, not NumberFormat: a German-locale JVM must not read 0.27 as 27.
            return new Load(
                    Double.parseDouble(fields[0]), Double.parseDouble(fields[1]), Double.parseDouble(fields[2]));
        } catch (final NumberFormatException e) {
            throw new IOException(file + " holds a load average that is not a number: '" + line + "'", e);
        }
    }

    private record Cpu(long total, long idle, int cpus) {}

    /**
     * The aggregate {@code cpu} line, plus a count of the per-core ones.
     *
     * Guest time is already inside user and nice, so only eight fields are summed; iowait counts as idle, as in htop.
     */
    private Cpu readCpu() throws IOException {
        final Path file = procRoot.resolve("stat");
        final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        long total = -1;
        long idle = -1;
        int cpus = 0;
        for (final String line : lines) {
            if (line.startsWith("cpu ")) {
                final String[] fields = WHITESPACE.splitAsStream(line).toArray(String[]::new);
                // fields[0] is "cpu"; eight numbers have to follow it.
                if (fields.length < 9) {
                    throw new IOException(file + " has an aggregate cpu line with " + (fields.length - 1)
                            + " fields, expected at least 8: '" + line + "'");
                }
                long sum = 0;
                for (int i = 1; i <= 8; i++) {
                    sum += parseJiffies(file, line, fields[i]);
                }
                total = sum;
                idle = parseJiffies(file, line, fields[4]) + parseJiffies(file, line, fields[5]);
            } else if (line.startsWith("cpu")) {
                cpus++;
            }
        }

        if (total < 0) {
            throw new IOException(file + " has no aggregate 'cpu ' line");
        }
        if (cpus == 0) {
            throw new IOException(file + " has no per-core 'cpuN' lines, so the host's CPU count" + " cannot be read");
        }
        return new Cpu(total, idle, cpus);
    }

    private static long parseJiffies(final Path file, final String line, final String field) throws IOException {
        try {
            return Long.parseLong(field);
        } catch (final NumberFormatException e) {
            throw new IOException(file + " has a cpu time that is not a number: '" + line + "'", e);
        }
    }

    /** The busy share since the previous reading; empty when there is nothing to subtract. */
    private OptionalDouble percentSince(final Cpu cpu) {
        if (previousTotalJiffies < 0) {
            return OptionalDouble.empty();
        }
        final long totalDelta = cpu.total() - previousTotalJiffies;
        final long idleDelta = cpu.idle() - previousIdleJiffies;
        if (totalDelta <= 0 || idleDelta < 0 || idleDelta > totalDelta) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(100.0 * (totalDelta - idleDelta) / totalDelta);
    }

    private record Memory(long total, long available, long free, long swapTotal, long swapFree) {}

    /** Kernel "kB" is KiB. */
    private static final long KIB = 1024L;

    private static final String MEM_TOTAL = "MemTotal";
    private static final String MEM_AVAILABLE = "MemAvailable";
    private static final String MEM_FREE = "MemFree";
    private static final String SWAP_TOTAL = "SwapTotal";
    private static final String SWAP_FREE = "SwapFree";

    /** Five lines of {@code /proc/meminfo}, in bytes: a missing memory field throws, a missing swap field is zero. */
    private Memory readMemory() throws IOException {
        final Path file = procRoot.resolve("meminfo");
        long total = -1;
        long available = -1;
        long free = -1;
        long swapTotal = 0;
        long swapFree = 0;

        for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            final int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            final String key = line.substring(0, colon);
            switch (key) {
                case MEM_TOTAL -> total = bytes(file, line);
                case MEM_AVAILABLE -> available = bytes(file, line);
                case MEM_FREE -> free = bytes(file, line);
                case SWAP_TOTAL -> swapTotal = bytes(file, line);
                case SWAP_FREE -> swapFree = bytes(file, line);
                default -> {}
            }
        }

        require(file, MEM_TOTAL, total);
        require(file, MEM_AVAILABLE, available);
        require(file, MEM_FREE, free);
        return new Memory(total, available, free, swapTotal, swapFree);
    }

    private static void require(final Path file, final String key, final long value) throws IOException {
        if (value < 0) {
            throw new IOException(file + " has no " + key + " line");
        }
    }

    /** {@code MemTotal: 16372536 kB} to bytes, refusing any unit other than {@code kB}. */
    private static long bytes(final Path file, final String line) throws IOException {
        final String[] fields = WHITESPACE
                .splitAsStream(line.substring(line.indexOf(':') + 1).strip())
                .toArray(String[]::new);
        if (fields.length != 2 || !"kB".equals(fields[1])) {
            throw new IOException(file + " has a line whose unit is not kB, and this parser will"
                    + " not guess at it: '" + line + "'");
        }
        try {
            return Long.parseLong(fields[0]) * KIB;
        } catch (final NumberFormatException e) {
            throw new IOException(file + " has a size that is not a number: '" + line + "'", e);
        }
    }
}
