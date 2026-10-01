package eu.nordtal.s2.steward.push;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Everything the stack reading found wrong, and three numbers it did not judge.
 *
 * @param diskPercent    percent of the disk in use, or null when it could not be read
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

    /** One thing the reading found wrong; {@code kind} is an {@link AlertType} key, or unknown. */
    public record Trigger(String kind, String level, String subject, String path) {}
}
