package eu.nordtal.season.smp.grave;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Keeps the startup restore from drawing a grave that the main thread changed while the restore read. */
final class GraveRestore {

    /** The graves the main thread created, settled or erased since {@link #begin()}, or null outside a restore. */
    private @Nullable Set<UUID> touched;

    /** Starts tracking. Call it on the main thread before the restore reads. */
    void begin() {
        touched = new HashSet<>();
    }

    /** Records that the main thread changed the grave. Main thread only. */
    void touched(final UUID graveId) {
        final Set<UUID> during = touched;
        if (during != null) {
            during.add(graveId);
        }
    }

    /** Stops tracking and returns the rows of {@code read} that the main thread left alone. Main thread only. */
    List<GraveRow> end(final List<GraveRow> read) {
        final Set<UUID> during = touched;
        touched = null;
        if (during == null) {
            return read;
        }
        return read.stream().filter(row -> !during.contains(row.id())).toList();
    }
}
