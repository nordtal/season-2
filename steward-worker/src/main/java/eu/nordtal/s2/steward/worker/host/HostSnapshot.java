package eu.nordtal.s2.steward.worker.host;

import java.util.OptionalDouble;

/**
 * How full the box is, at one instant: one reading of {@code /proc} and one {@code statvfs}.
 *
 * <h2>Everything here is the HOST's, not this container's</h2>
 * Measured on {@code dev.nordtal.eu} on 2026-09-12 from inside {@code nordtal-s2-updater-1}, an
 * ordinary container with no added privileges and no Docker socket: {@code /proc/meminfo} there
 * printed {@code MemTotal: 16372536 kB}, which is the host's 16 GB and not any cgroup limit;
 * {@code /proc/stat} carried six {@code cpuN} lines, which is the host's {@code nproc}; and
 * {@code df -B1 /} answered the host's {@code /dev/sda1} to the byte. That is why this reader needs
 * no privilege, no {@code /sys} mount and no Docker access - which matters, because the container
 * that would otherwise have to be trusted with those is the one whose job is downloading files from
 * the internet.
 *
 * <p><b>The cost of that, stated plainly:</b> these numbers describe the machine, so they do not
 * narrow to "what this stack is using". A page built on them answers "is the box full", never
 * "which of our services is eating it".</p>
 *
 * <h2>Units are bytes, all of them</h2>
 * {@code /proc/meminfo} is in kB per line and {@code /proc/loadavg} is unitless; the conversion
 * happens in {@link HostMetrics} so that nothing downstream ever has to ask. The kernel's "kB" is
 * KiB - 1024 - and the check is arithmetic rather than faith: 16372536 * 1024 is 16765476864, which
 * is exactly what {@code free -b} printed on the same host in the same minute.
 *
 * @param load1                the 1-minute load average, as {@code /proc/loadavg} gives it. Compare
 *                             it against {@link #cpus()} and not against 1 - on this six-core host
 *                             a load of 3 is three tasks' worth of demand against six CPUs, which
 *                             is a machine with room. It is <b>not</b> "half idle", and the
 *                             difference is not pedantry: Linux counts uninterruptible sleep in the
 *                             load average, so a host whose disk has stalled climbs past its core
 *                             count with every CPU doing nothing. For how busy the CPUs were, read
 *                             {@link #cpuPercent()}, which is the only number here that measures
 *                             that.
 * @param load5                the 5-minute load average.
 * @param load15               the 15-minute load average.
 * @param cpus                 how many CPUs the HOST has, counted as {@code cpuN} lines in
 *                             {@code /proc/stat}. Deliberately not {@code availableProcessors()}:
 *                             that one honours this container's cgroup quota, and dividing a
 *                             host-wide load average by a per-container core count is a graph that
 *                             is wrong by whatever the quota happens to be.
 * @param cpuPercent           how busy the CPUs were <em>since the previous reading</em>, 0..100 -
 *                             or {@linkplain OptionalDouble#empty() absent} when there is no
 *                             previous reading to subtract. See {@link HostMetrics#read()}: the
 *                             first call after startup genuinely cannot know this, and a chart that
 *                             opens at 0.0 because nothing was measured is a lie the whole page
 *                             then inherits.
 * @param memoryTotalBytes     {@code MemTotal}.
 * @param memoryAvailableBytes {@code MemAvailable} - what a new process could get without pushing
 *                             the machine into swap. <b>This is the one a "memory used" bar must be
 *                             built from</b>, because page cache is not memory anybody is short of.
 * @param memoryFreeBytes      {@code MemFree} - memory holding nothing at all. Always far smaller
 *                             than available on a machine that has been up a while (1.4 GB against
 *                             5.4 GB here, measured 2026-09-12) and worth showing next to it for
 *                             exactly that reason: they are different questions.
 * @param swapTotalBytes       {@code SwapTotal}, {@code 0} where there is no swap - which is the
 *                             case on this host, measured 2026-09-12.
 * @param swapFreeBytes        {@code SwapFree}, {@code 0} likewise.
 * @param diskTotalBytes       the filesystem's size, from {@code FileStore#getTotalSpace()}.
 * @param diskUsedBytes        {@code total - unallocated}, which is the column {@code df} calls
 *                             "Used" - see {@link HostMetrics} for why it is not
 *                             {@code total - usable}.
 * @param diskFreeBytes        {@code FileStore#getUsableSpace()} - what we could actually write.
 *                             <b>{@code used + free} is therefore smaller than {@code total}</b>, by
 *                             the root reserve (16.8 MB of 208 GB here, measured 2026-09-12).
 *                             {@code df} has the same gap for the same reason; a page that computes
 *                             a percentage from two of these three should say which two.
 */
public record HostSnapshot(
        double load1, double load5, double load15, int cpus,
        OptionalDouble cpuPercent,
        long memoryTotalBytes, long memoryAvailableBytes, long memoryFreeBytes,
        long swapTotalBytes, long swapFreeBytes,
        long diskTotalBytes, long diskUsedBytes, long diskFreeBytes) {
}
