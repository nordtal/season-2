package eu.nordtal.season.stewardagent.measure;

import java.util.OptionalDouble;

/**
 * How full the host is at one instant, in bytes: the machine's numbers, read without privilege, never this stack's.
 *
 * @param load1 the 1-minute load average, to compare with {@link #cpus()}
 * @param load5 the 5-minute load average
 * @param load15 the 15-minute load average
 * @param cpus the host's CPUs from {@code /proc/stat}, not the container's cgroup quota
 * @param cpuPercent CPU use since the previous reading, 0..100, or empty without one
 * @param memoryTotalBytes {@code MemTotal}
 * @param memoryAvailableBytes {@code MemAvailable}, the base of a "memory used" bar
 * @param memoryFreeBytes {@code MemFree}
 * @param swapTotalBytes {@code SwapTotal}, {@code 0} without swap
 * @param swapFreeBytes {@code SwapFree}, {@code 0} without swap
 * @param diskTotalBytes the size of the filesystem
 * @param diskUsedBytes total minus unallocated, which {@code df} calls "Used"
 * @param diskFreeBytes the usable space, so {@code used + free} is less than {@code total} by the root reserve
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
