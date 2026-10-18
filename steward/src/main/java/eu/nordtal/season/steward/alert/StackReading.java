package eu.nordtal.season.steward.alert;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * @param registryProblem the reason the images were not compared, or null when the registry answered
 * @param mountedBy the services that run on each saved volume, keyed by the name its archives carry
 * @param deadPack the resource pack URL the proxy offers when it does not answer, or null when it does or none is set
 */
public record StackReading(
        List<Service> services,
        @Nullable String registryProblem,
        List<Archive> archives,
        @Nullable Host host,
        List<String> backupVolumes,
        Map<String, List<String>> mountedBy,
        @Nullable String deadPack) {

    public StackReading {
        services = List.copyOf(services);
        archives = List.copyOf(archives);
        backupVolumes = List.copyOf(backupVolumes);
        mountedBy = Map.copyOf(mountedBy);
    }

    /**
     * @param exitCode the exit code of its last run, or null while it runs or when unknown
     * @param restarts how often Docker restarted it since it was last started on purpose
     * @param startedAt when its current run started, or null when it does not run
     */
    public record Service(
            String name,
            String state,
            @Nullable String health,
            Purpose purpose,
            @Nullable Integer exitCode,
            boolean outdated,
            int restarts,
            @Nullable Instant startedAt) {

        public Service(
                final String name,
                final String state,
                final @Nullable String health,
                final Purpose purpose,
                final @Nullable Integer exitCode,
                final boolean outdated) {
            this(name, state, health, purpose, exitCode, outdated, 0, null);
        }

        public Service(
                final String name,
                final String state,
                final @Nullable String health,
                final Purpose purpose,
                final boolean outdated) {
            this(name, state, health, purpose, null, outdated);
        }
    }

    /** Says what a service is for, which decides what its state means. */
    public enum Purpose {
        /** Should run, so a stop is an outage. */
        SERVES,
        /** Is meant to be stopped: a standby, or a service an admin holds down. */
        RESTS,
        /** Runs once and exits, so only a failed exit is wrong. */
        ONCE,
        /** An update run stops and starts it now, or did a moment ago. */
        MOVING
    }

    /** A partial file is still being written. */
    public record Archive(String name, Instant modified, boolean partial, boolean offsite) {}

    public record Host(long diskUsed, long diskTotal, long memoryAvailable, long memoryTotal) {}
}
