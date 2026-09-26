package eu.nordtal.s2.steward.ui.push;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What {@code GET /api/alert-level} answers: everything steward-worker found wrong, and the three
 * numbers it measured but did not judge.
 *
 * <h2>Why this is not steward-worker's {@code AlertLevel.Reading}</h2>
 * That type lives in {@code :steward-worker} and this module does not depend on it - the two
 * services talk JSON over {@code /api/alert-level}, the same boundary {@code InternalClient}'s own
 * class note draws for every other route. A shared Java type here would mean the jar of one service
 * on the classpath of the other, which is exactly the coupling {@code :steward-ui}'s test-only
 * dependency on {@code :steward-worker} already warns against outside of tests.
 *
 * <h2>Measurements on one side, thresholds on the other</h2>
 * {@link #diskPercent}, {@link #memoryPercent} and {@link #backupAgeHours} are readings, not
 * alarms. The numbers they are held against are {@code UiSpec.AlertSpec}'s, they live in this
 * process, and {@link Alerts} is where the two meet. That split is Till's decision of 2026-09-19 on
 * steward/98's open scoping question, and its point is that every one of those three numbers exists
 * exactly once in the whole stack.
 *
 * <p>Strings rather than enums for {@code kind} and {@code level}: the worker already turns its own
 * enums into lowercase text before this ever sees it, and a second enum here would be a second
 * place that has to agree with the first on spelling. {@link AlertType#of} does the one translation
 * that is needed, once, and answers null for a word it does not know rather than throwing inside a
 * background poll.</p>
 *
 * @param diskPercent    percent of the disk in use, or null when the worker could not read it
 * @param memoryPercent  percent of host memory in use, or null for the same reason
 * @param backupAgeHours how old the most neglected finished backup series is, or null when there is
 *                       no finished backup at all - which is a {@code backup} trigger already
 */
public record AlertReading(
        @NotNull List<Trigger> triggers,
        @Nullable Double diskPercent,
        @Nullable Double memoryPercent,
        @Nullable Double backupAgeHours) {

    public AlertReading {
        triggers = List.copyOf(triggers);
    }

    /** One thing the worker found wrong. {@code kind} is {@link AlertType}'s own key, or unknown. */
    public record Trigger(
            @NotNull String kind,
            @NotNull String level,
            @NotNull String subject,
            @NotNull String path) {}
}
