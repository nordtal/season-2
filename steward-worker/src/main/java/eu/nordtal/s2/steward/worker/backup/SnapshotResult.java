package eu.nordtal.s2.steward.worker.backup;

import java.time.Duration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What saving one thing came to.
 *
 * <p><b>{@code bytes} is on this record because a backup that saved nothing has to be able to say
 * so.</b> Run 23 once reported success having snapshotted zero volumes, and nothing in the report
 * made that visible. A size of zero is not a detail here; it is the finding.</p>
 *
 * @param name     what was saved - a volume name, or {@code database}
 * @param ok       whether there is now a file that can be restored from
 * @param bytes    how large it is. Zero with {@code ok} true is a contradiction the report shows
 * @param took     how long it ran, because "the stack was down for 66 seconds" is half the story
 * @param file     where it landed, or {@code null} when nothing was written
 * @param message  what happened, in a sentence a person can act on
 */
public record SnapshotResult(
        @NotNull String name,
        boolean ok,
        long bytes,
        @NotNull Duration took,
        @Nullable String file,
        @NotNull String message) {

    public static SnapshotResult saved(
            final @NotNull String name, final long bytes, final @NotNull Duration took, final @NotNull String file) {
        return new SnapshotResult(
                name, true, bytes, took, file, "saved " + human(bytes) + " in " + took.toSeconds() + "s");
    }

    public static SnapshotResult failed(
            final @NotNull String name, final @NotNull Duration took, final @NotNull String message) {
        return new SnapshotResult(name, false, 0, took, null, message);
    }

    /** A size a person reads at 04:45, not a number of bytes. */
    public static String human(final long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        final String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes / 1024.0;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
