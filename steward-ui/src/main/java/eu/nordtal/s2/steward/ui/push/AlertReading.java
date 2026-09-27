package eu.nordtal.s2.steward.ui.push;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What {@code GET /api/alert-level} answers: everything the worker found wrong, and three numbers it did not judge.
 *
 * @param diskPercent    percent of the disk in use, or null when the worker could not read it
 * @param memoryPercent  percent of host memory in use, or null for the same reason
 * @param backupAgeHours how old the most neglected finished backup series is, or null when there is
 *                       no finished backup at all
 */
public record AlertReading(
        List<Trigger> triggers,
        @Nullable Double diskPercent,
        @Nullable Double memoryPercent,
        @Nullable Double backupAgeHours) {

    public AlertReading {
        triggers = List.copyOf(triggers);
    }

    /** One thing the worker found wrong; {@code kind} is an {@link AlertType} key, or unknown. */
    public record Trigger(String kind, String level, String subject, String path) {}
}
