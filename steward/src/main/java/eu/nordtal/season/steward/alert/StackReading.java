package eu.nordtal.season.steward.alert;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What the stack looks like right now, as far as an alert can be about it.
 *
 * @param registryProblem why the images were not compared, or null when the registry answered
 * @param host the machine's numbers, or null when they could not be read
 * @param backupVolumes the volumes a backup saves now, by the name their archives carry
 * @param mountedBy for each saved volume, by the name its archives carry, the services that run on it
 */
public record StackReading(
        List<Service> services,
        @Nullable String registryProblem,
        List<Archive> archives,
        @Nullable Host host,
        List<String> backupVolumes,
        Map<String, List<String>> mountedBy) {

    public StackReading {
        services = List.copyOf(services);
        archives = List.copyOf(archives);
        backupVolumes = List.copyOf(backupVolumes);
        mountedBy = Map.copyOf(mountedBy);
    }

    /**
     * One compose service.
     *
     * @param health Docker's health word, or null for a container without a check
     * @param quiet whether a stop is meant: a standby, or a service an admin holds down
     * @param outdated whether it runs an older image than the registry has
     */
    public record Service(
            String name, String state, @Nullable String health, boolean quiet, boolean outdated) {}

    /**
     * One file in the backup folder; a partial one is still being written.
     *
     * @param offsite whether a copy of it is in the offsite repository
     */
    public record Archive(String name, Instant modified, boolean partial, boolean offsite) {}

    /** The disk and memory numbers, in bytes. */
    public record Host(long diskUsed, long diskTotal, long memoryAvailable, long memoryTotal) {}
}
