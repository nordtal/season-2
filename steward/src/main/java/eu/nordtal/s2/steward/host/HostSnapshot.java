package eu.nordtal.s2.steward.host;

import java.util.OptionalDouble;

/**
 * How full the host is at one instant, in bytes: the machine's numbers, read without privilege, never this stack's.
 *
 * @param load1 the 1-minute load average; compare it with {@link #cpus()}, and read {@link #cpuPercent()} for CPU use
 * @param load5 the 5-minute load average
 * @param load15 the 15-minute load average
 * @param cpus the host's CPUs from {@code /proc/stat}, not the container's cgroup quota
 * @param cpuPercent CPU use since the previous reading, 0..100, or empty without one
 * @param memoryTotalBytes {@code MemTotal}
 * @param memoryAvailableBytes {@code MemAvailable}, which a "memory used" bar is built from
 * @param memoryFreeBytes {@code MemFree}, memory holding nothing at all
 * @param swapTotalBytes {@code SwapTotal}, {@code 0} where there is no swap
 * @param swapFreeBytes {@code SwapFree}, {@code 0} likewise
 * @param diskTotalBytes the filesystem's size
 * @param diskUsedBytes {@code total} minus unallocated, which {@code df} calls "Used"
 * @param diskFreeBytes the usable space, so {@code used + free} falls short of {@code total} by the root reserve
 */
public record HostSnapshot(
        double load1,
        double load5,
        double load15,
        int cpus,
        OptionalDouble cpuPercent,
        long memoryTotalBytes,
        long memoryAvailableBytes,
        long memoryFreeBytes,
        long swapTotalBytes,
        long swapFreeBytes,
        long diskTotalBytes,
        long diskUsedBytes,
        long diskFreeBytes) {}
