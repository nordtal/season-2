package eu.nordtal.s2.steward.worker.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Reads {@link HostSnapshot} out of {@code /proc} and one {@code statvfs}. Cheap enough to call on a
 * timer, and it holds exactly one piece of state: the previous CPU counters.
 *
 * <h2>Why files and not a library</h2>
 * Because the four files below are the same four files {@code uptime}, {@code free} and {@code top}
 * read, they are present in every container on this host without being mounted, and reading them
 * needs no privilege - measured 2026-09-12 inside {@code nordtal-s2-updater-1}, which has no added
 * capabilities and no Docker socket. An OSHI-sized dependency would buy portability to a platform
 * this project does not deploy to: production is one {@code docker compose} stack on one Linux box.
 *
 * <p><b>The cost is that this is Linux-only and silently so.</b> On a machine without
 * {@code /proc/meminfo} every call throws {@link java.nio.file.NoSuchFileException}, which is at
 * least loud; it is not, however, a fallback.</p>
 *
 * <h2>The CPU percentage is a delta, and that shapes the whole class</h2>
 * {@code /proc/stat} counts jiffies since boot, so a single reading says how busy the machine has
 * been <em>since it was switched on</em> - a number that is true, useless and changes by nothing.
 * The percentage anybody wants is the difference between two readings divided by the time between
 * them, which means this object has to remember the last one. Two consequences worth knowing before
 * calling it:
 *
 * <ul>
 *   <li>{@link HostSnapshot#cpuPercent()} is {@linkplain OptionalDouble#empty() empty} on the first
 *       call, and on any call that follows the previous one too closely to have accumulated a
 *       single jiffy. The alternative - reporting {@code 0.0} - is a measurement nobody made.</li>
 *   <li>The interval is whatever the caller's interval is. Sampling every 5 s gives a 5 s average;
 *       sampling twice in a row gives the load of that microsecond, which is noise. This class does
 *       not own a clock and deliberately does not schedule itself.</li>
 * </ul>
 *
 * <p>{@link #read()} is {@code synchronized} for that state alone: two threads reading at once would
 * each consume half of the other's interval and both report something that never happened.</p>
 */
public final class HostMetrics {

    /**
     * Where the kernel's own numbers are. A constructor parameter rather than a constant because
     * the tests feed a captured copy - see {@code HostMetricsTest}.
     */
    private static final String PROC = "/proc";

    /**
     * The filesystem measured by default.
     *
     * <p><b>{@code /} inside a container is the right answer here and that is measured, not
     * assumed</b> (2026-09-12): the container's root is an overlay whose {@code statvfs} reports the
     * backing filesystem, so {@code df -B1 /} inside {@code nordtal-s2-updater-1} printed the host's
     * {@code /dev/sda1} figures - 207929917440 total, 13749350400 used - byte for byte identical to
     * {@code df -B1 /} on the host itself. The mounted volumes live on that same filesystem, so
     * pointing this at {@code /volumes} answers the same numbers; the second constructor exists for
     * the day that stops being true (a separate disk for world data would be exactly that day).</p>
     */
    private static final String DISK = "/";

    private final Path procRoot;
    private final Path diskPath;

    /**
     * The previous {@code /proc/stat} totals, or {@code -1} when there has not been a reading yet.
     * {@code -1} rather than a boolean flag because it is one field to keep consistent instead of
     * three.
     */
    private long previousTotalJiffies = -1;

    private long previousIdleJiffies = -1;

    /** Reads the real {@code /proc} and the filesystem holding {@code /}. */
    public HostMetrics() {
        this(Path.of(PROC), Path.of(DISK));
    }

    /**
     * @param procRoot a directory holding {@code loadavg}, {@code stat} and {@code meminfo} in the
     *                 kernel's format. The tests point this at captured text.
     * @param diskPath any path on the filesystem to measure.
     */
    public HostMetrics(final Path procRoot, final Path diskPath) {
        this.procRoot = procRoot;
        this.diskPath = diskPath;
    }

    /**
     * One reading of everything.
     *
     * @return the snapshot, whose {@link HostSnapshot#cpuPercent()} is absent on the first call
     * @throws IOException if a file is missing, or holds something this parser will not guess at -
     *                     a field that is not there, a unit that is not {@code kB}. Both messages
     *                     name the file and the line, because "NumberFormatException: null" from a
     *                     background sampler is a morning spent grepping.
     */
    public synchronized HostSnapshot read() throws IOException {
        final Load load = readLoad();
        final Cpu cpu = readCpu();
        final Memory memory = readMemory();

        final OptionalDouble percent = percentSince(cpu);
        previousTotalJiffies = cpu.total();
        previousIdleJiffies = cpu.idle();

        // getUsableSpace() and NOT getUnallocatedSpace() for the free figure: the two differ by the
        // root reserve (16.8 MB of 208 GB here, measured 2026-09-12), and a process running as
        // anybody but root cannot write into that reserve - so unallocated would promise space this
        // container does not have. `used` goes the other way and is total - unallocated, because
        // that reserve IS occupied as far as the filesystem is concerned; it is also exactly how
        // `df` computes its Used column, which is the number a person will compare ours against.
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

    // ------------------------------------------------------------------ /proc/loadavg

    private record Load(double one, double five, double fifteen) {}

    /**
     * {@code 0.27 0.31 0.32 1/914 2752038} - three averages, then runnable/total tasks and the last
     * PID, neither of which anybody is asking about on a "how full is the box" page.
     */
    private Load readLoad() throws IOException {
        final Path file = procRoot.resolve("loadavg");
        final String line = Files.readString(file, StandardCharsets.UTF_8).strip();
        final String[] fields = line.split("\\s+");
        if (fields.length < 3) {
            throw new IOException(file + " does not hold three load averages: '" + line + "'");
        }
        try {
            // Double.parseDouble and not NumberFormat: the kernel writes C-locale decimal points,
            // and a JVM started in a German locale must not read 0.27 as 27.
            return new Load(
                    Double.parseDouble(fields[0]), Double.parseDouble(fields[1]), Double.parseDouble(fields[2]));
        } catch (final NumberFormatException e) {
            throw new IOException(file + " holds a load average that is not a number: '" + line + "'", e);
        }
    }

    // ------------------------------------------------------------------ /proc/stat

    private record Cpu(long total, long idle, int cpus) {}

    /**
     * The aggregate {@code cpu} line, plus a count of the per-core ones.
     *
     * <p>Fields are, in order, {@code user nice system idle iowait irq softirq steal guest
     * guest_nice}. <b>Only the first eight are summed</b>: the kernel already counts guest time
     * inside {@code user} and {@code guest_nice} inside {@code nice}, so adding them again inflates
     * the total and quietly depresses every percentage on a host that runs VMs.</p>
     *
     * <p><b>{@code iowait} counts as idle here</b>, which is a decision and not an oversight: a CPU
     * waiting for the disk is a CPU doing nothing, and counting it as busy would paint the nightly
     * {@code postgres-backup} as a pegged machine. It is the same convention {@code htop} uses. The
     * cost is that a box thrashing its disk looks idle on this page - which is what the load average
     * next to it is for, because iowait does raise that.</p>
     */
    private Cpu readCpu() throws IOException {
        final Path file = procRoot.resolve("stat");
        final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        long total = -1;
        long idle = -1;
        int cpus = 0;
        for (final String line : lines) {
            if (line.startsWith("cpu ")) {
                final String[] fields = line.split("\\s+");
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

    /**
     * The busy share of the interval between the previous reading and this one.
     *
     * <p>Empty in three cases, and they are one case: there is nothing to subtract. No previous
     * reading; no jiffy elapsed since it (two reads inside one 10 ms tick, which a caller sampling
     * on a timer will never hit and a test can); or counters that went backwards, which a real
     * kernel does not do but a CPU going offline and a re-pointed {@code procRoot} both can.</p>
     */
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

    // ------------------------------------------------------------------ /proc/meminfo

    private record Memory(long total, long available, long free, long swapTotal, long swapFree) {}

    /** Kernel "kB" is KiB. {@code 16372536 * 1024} is {@code free -b}'s 16765476864 exactly. */
    private static final long KIB = 1024L;

    private static final String MEM_TOTAL = "MemTotal";
    private static final String MEM_AVAILABLE = "MemAvailable";
    private static final String MEM_FREE = "MemFree";
    private static final String SWAP_TOTAL = "SwapTotal";
    private static final String SWAP_FREE = "SwapFree";

    /**
     * Five of the fifty-odd lines of {@code /proc/meminfo}, in bytes.
     *
     * <p><b>A missing memory field throws</b> rather than defaulting, because
     * {@link HostSnapshot}'s fields are {@code long} and there is no honest value to put there -
     * {@code MemAvailable} in particular has existed since Linux 3.14 and its absence means this is
     * not the file we think it is, not that the host has no available memory.</p>
     *
     * <p><b>A missing SWAP field is zero</b>, and that is not the same compromise: a kernel built
     * without {@code CONFIG_SWAP} prints no {@code SwapTotal} line at all, and "no swap" is
     * precisely and only what zero bytes of swap means. This host prints the lines and they say
     * {@code 0 kB} (measured 2026-09-12), so both spellings of swapless land on the same answer.</p>
     */
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
                default -> {
                    // Forty-odd lines nobody on this page is asking about.
                }
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

    /**
     * {@code MemTotal:       16372536 kB} to bytes.
     *
     * <p>The unit is checked and not assumed. Every line of this file that carries a size says
     * {@code kB} today, but a few (the {@code HugePages_*} counters) carry no unit at all, and a
     * parser that skips the check would read a future line in some other unit as kilobytes and be
     * wrong by a factor nobody would spot on a dashboard.</p>
     */
    private static long bytes(final Path file, final String line) throws IOException {
        final String[] fields = line.substring(line.indexOf(':') + 1).strip().split("\\s+");
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
