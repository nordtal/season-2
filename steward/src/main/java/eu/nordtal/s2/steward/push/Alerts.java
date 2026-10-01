package eu.nordtal.s2.steward.push;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Applies this service's thresholds to the stack's raw reading, one alert per {@link AlertType}.
 *
 * A missing measurement is never over; several triggers of one type merge at the worst level.
 */
final class Alerts {

    /** The three configured numbers, out of {@code WebSpec.AlertSpec}. */
    record Thresholds(int diskPercent, int memoryPercent, int backupAgeHours) {}

    /**
     * One notification-sized fact.
     *
     * @param level {@code warn} or {@code down}; an alert that is over is absent from the map rather than green
     */
    record Alert(AlertType type, String level, String subject, String path) {}

    private Alerts() {}

    static Map<AlertType, Alert> of(final AlertReading reading, final Thresholds thresholds) {
        final Map<AlertType, List<AlertReading.Trigger>> byType = new EnumMap<>(AlertType.class);
        for (final AlertReading.Trigger trigger : reading.triggers()) {
            final AlertType type = AlertType.of(trigger.kind());
            if (type == null) {
                // A kind this release does not know; dropped rather than guessed at.
                continue;
            }
            byType.computeIfAbsent(type, ignored -> new ArrayList<>()).add(trigger);
        }

        // The backup age joins the same type as a missing backup; a presence trigger already there wins.
        if (!byType.containsKey(AlertType.BACKUP)
                && reading.backupAgeHours() != null
                && reading.backupAgeHours() > thresholds.backupAgeHours()) {
            byType.put(
                    AlertType.BACKUP,
                    List.of(new AlertReading.Trigger(
                            AlertType.BACKUP.key(), "down", "backups", "/operations/backups")));
        }

        // `/`, not `/operations`: the host numbers are on the start page.
        if (over(reading.diskPercent(), thresholds.diskPercent())) {
            byType.put(AlertType.DISK, List.of(new AlertReading.Trigger(AlertType.DISK.key(), "warn", "disk", "/")));
        }
        if (over(reading.memoryPercent(), thresholds.memoryPercent())) {
            byType.put(
                    AlertType.MEMORY, List.of(new AlertReading.Trigger(AlertType.MEMORY.key(), "warn", "memory", "/")));
        }

        final Map<AlertType, Alert> alerts = new EnumMap<>(AlertType.class);
        byType.forEach((type, triggers) -> alerts.put(type, merge(type, triggers)));
        return alerts;
    }

    /**
     * Whether a percentage is at or past its threshold, matching {@code health.ts}'s {@code >=}; null is never over.
     */
    private static boolean over(final @Nullable Double measured, final int threshold) {
        return measured != null && measured >= threshold;
    }

    private static Alert merge(final AlertType type, final List<AlertReading.Trigger> triggers) {
        AlertReading.Trigger worst = triggers.getFirst();
        for (final AlertReading.Trigger trigger : triggers) {
            if ("down".equals(trigger.level())) {
                worst = trigger;
                break;
            }
        }
        final List<String> subjects = new ArrayList<>();
        for (final AlertReading.Trigger trigger : triggers) {
            if (!trigger.subject().isBlank() && !subjects.contains(trigger.subject())) {
                subjects.add(trigger.subject());
            }
        }
        return new Alert(type, worst.level(), String.join(", ", subjects), worst.path());
    }
}
