package eu.nordtal.s2.steward.ui.push;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What {@code GET /api/alert-level} answers.
 *
 * Everything steward-worker found wrong, and the three numbers it measured but did not judge.
 * Not steward-worker's own {@code AlertLevel.Reading}: this module does not depend on that jar, so
 * the two services talk JSON instead. Strings rather than enums for {@code kind} and {@code level},
 * since the worker already turns its own enums into lowercase text; {@link AlertType#of} does the
 * one translation needed and answers null for a word it does not know.
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

    /** One thing the worker found wrong. {@code kind} is {@link AlertType}'s own key, or unknown. */
    public record Trigger(String kind, String level, String subject, String path) {}
}
