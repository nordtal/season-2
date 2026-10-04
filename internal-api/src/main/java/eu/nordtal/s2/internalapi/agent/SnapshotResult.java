package eu.nordtal.s2.internalapi.agent;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * What saving one thing came to; zero {@code bytes} with {@code ok} true is a finding, not a detail.
 *
 * @param name what was saved: a volume name, or {@code database}
 * @param ok whether there is now a file that can be restored from
 * @param bytes how large it is, which a report tells through its own words for a size
 * @param took how long it ran, likewise
 * @param file where it landed, or {@code null} when nothing was written
 * @param message why it failed, in the words of whatever failed, or {@code null} when it saved
 */
public record SnapshotResult(
        String name,
        boolean ok,
        long bytes,
        Duration took,
        @Nullable String file,
        @Nullable String message) {

    public static SnapshotResult saved(final String name, final long bytes, final Duration took, final String file) {
        return new SnapshotResult(name, true, bytes, took, file, null);
    }

    public static SnapshotResult failed(final String name, final Duration took, final String message) {
        return new SnapshotResult(name, false, 0, took, null, message);
    }
}
