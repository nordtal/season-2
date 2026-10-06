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
     * @param exitCode what its last run exited with, or null while it runs or when that is not known
     * @param outdated whether it runs an older image than the registry has
     */
    public record Service(
            String name,
            String state,
            @Nullable String health,
            Purpose purpose,
            @Nullable Integer exitCode,
            boolean outdated) {

        /** A long-running service with no last exit, which is how most of the stack reads. */
        public Service(
                final String name,
                final String state,
                final @Nullable String health,
                final Purpose purpose,
                final boolean outdated) {
            this(name, state, health, purpose, null, outdated);
        }
    }

    /** What a service is for right now, which decides what its state means. */
    public enum Purpose {
        /** It should run, so a stop is an outage. */
        SERVES,
        /** Its stop is meant: a standby, or a service an admin holds down. */
        RESTS,
        /** It runs once and exits, so only a failed exit is wrong. */
        ONCE,
        /** A run stops and starts it right now, or did a moment ago. */
        MOVING
    }

    /**
     * One file in the backup folder; a partial one is still being written.
     *
     * @param offsite whether a copy of it is in the offsite repository
     */
    public record Archive(String name, Instant modified, boolean partial, boolean offsite) {}

    /** The disk and memory numbers, in bytes. */
    public record Host(long diskUsed, long diskTotal, long memoryAvailable, long memoryTotal) {}
}
