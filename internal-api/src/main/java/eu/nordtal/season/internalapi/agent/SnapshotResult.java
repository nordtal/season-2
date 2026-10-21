package eu.nordtal.season.internalapi.agent;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * The result of saving one thing. Zero {@code bytes} with {@code ok} true is a finding.
 *
 * @param name a volume name, or {@code database}
 * @param ok whether a file exists that can be restored from
 * @param bytes the size of the file
 * @param took how long the save ran
 * @param file the path of the file, or {@code null} if nothing was written
 * @param message the reason of the failure, in the words of the failing tool, or {@code null} if it saved
 * @param exitCode what the failing tool exited with, or {@code null} if it saved or no tool gave a code
 */
public record SnapshotResult(
        String name,
        boolean ok,
        long bytes,
        Duration took,
        @Nullable String file,
        @Nullable String message,
        @Nullable Integer exitCode) {

    public static SnapshotResult saved(final String name, final long bytes, final Duration took, final String file) {
        return new SnapshotResult(name, true, bytes, took, file, null, null);
    }

    public static SnapshotResult failed(final String name, final Duration took, final String message) {
        return new SnapshotResult(name, false, 0, took, null, message, null);
    }

    public static SnapshotResult failed(
            final String name, final Duration took, final int exitCode, final String message) {
        return new SnapshotResult(name, false, 0, took, null, message, exitCode);
    }
}
