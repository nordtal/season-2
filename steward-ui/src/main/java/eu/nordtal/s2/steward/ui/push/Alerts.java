package eu.nordtal.s2.steward.ui.push;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Where the worker's raw reading meets this service's own thresholds, and becomes one alert per
 * {@link AlertType}.
 *
 * <h2>This is the policy half, and it is on this side on purpose</h2>
 * steward-worker sends what it measured - disk percent, memory percent, the age of the most
 * neglected backup series - and never what it thinks of those numbers, because the numbers it would
 * have to think with are {@code UiSpec.AlertSpec}'s and live here. Till decided the scope question
 * steward/98 left open on 2026-09-19: threshold alarms do push, and they do it without a second
 * copy of any threshold existing anywhere.
 *
 * <p>A missing measurement is skipped, never treated as zero and never treated as over: the worker
 * leaves the key out exactly when {@code /proc} could not be read, and "nobody looked" must not
 * reach a lock screen as "all clear" or as an alarm. That is {@code health.ts}'s own rule about
 * {@code DEFAULT_THRESHOLDS} not being a fallback, one process further along.</p>
 *
 * <h2>One alert per type, even when several triggers of that type fired</h2>
 * Two stopped services are one errand and one notification. The level is the worst of them, the
 * subject is the list, and the tap lands on the worst one's page - a notification that says "smp,
 * limbo" and opens the page of the one that is red is more use than one that opens a summary.
 */
final class Alerts {

    /** The three configured numbers, out of {@code UiSpec.AlertSpec}. */
    record Thresholds(int diskPercent, int memoryPercent, int backupAgeHours) {}

    /**
     * One notification-sized fact.
     *
     * @param level {@code warn} or {@code down} - never {@code ok}. An alert that is over is absent
     *              from the map rather than present and green, so that {@link AlertWatch} can tell
     *              "still wrong, differently" from "no longer wrong" by presence alone.
     */
    record Alert(
            @NotNull AlertType type,
            @NotNull String level,
            @NotNull String subject,
            @NotNull String path) {}

    private Alerts() {}

    static @NotNull Map<AlertType, Alert> of(
            final @NotNull AlertReading reading, final @NotNull Thresholds thresholds) {
        final Map<AlertType, List<AlertReading.Trigger>> byType = new EnumMap<>(AlertType.class);
        for (final AlertReading.Trigger trigger : reading.triggers()) {
            final AlertType type = AlertType.of(trigger.kind());
            if (type == null) {
                // A kind this release does not know. Dropped rather than guessed at: there is no
                // switch for it, so there is nobody who could have consented to being woken by it.
                continue;
            }
            byType.computeIfAbsent(type, ignored -> new ArrayList<>()).add(trigger);
        }

        // The backup AGE, which is the half of the backup type that only this process can judge.
        // It joins the same type as a missing backup rather than making a sixth one - see
        // AlertType's own note on why that is one switch and not two. A presence trigger already in
        // the list wins: "there is no database dump" is the more specific sentence, and both are
        // red, so nothing is lost by not saying the second one as well.
        if (!byType.containsKey(AlertType.BACKUP)
                && reading.backupAgeHours() != null
                && reading.backupAgeHours() > thresholds.backupAgeHours()) {
            byType.put(
                    AlertType.BACKUP,
                    List.of(new AlertReading.Trigger(
                            AlertType.BACKUP.key(), "down", "backups", "/operations/backups")));
        }

        // Disk and memory. `/` and not `/operations`: the host numbers are on the start page, which
        // is also the page somebody opening Steward from a lock screen at night wants first.
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
     * Whether a percentage is at or past its threshold.
     *
     * <p>{@code >=}, the same comparison {@code health.ts} makes for both percentages, so that a
     * disk at exactly 85 % says the same thing on the start page and on a phone. The backup age
     * above is compared with a strict {@code >} for exactly the same reason - that is the
     * comparison {@code tooOld} makes - and the asymmetry is copied rather than tidied up, because
     * the tile and the lock screen disagreeing is the only failure worth avoiding here. Null is
     * never over - see the class note.</p>
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
