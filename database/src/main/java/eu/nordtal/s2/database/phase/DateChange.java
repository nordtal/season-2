package eu.nordtal.s2.database.phase;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What one call to {@link PhaseDirectory#setLaunch} or {@link PhaseDirectory#setSmpStart} did.
 *
 * @param previous the instant the column held before, {@code null} when it was not set
 * @param current  the instant it holds now, {@code null} when the date was cleared
 * @param grants   how many {@code access_grant} rows were moved with it; only {@code smp_start} moves any
 * @param accounts how many distinct Discord accounts those rows belong to
 */
public record DateChange(
        @Nullable Instant previous, @Nullable Instant current, int grants, int accounts) {

    /** Returns whether the write asked for the value the column already held. */
    public boolean unchanged() {
        return previous == null ? current == null : previous.equals(current);
    }

    /** Returns whether this write moved any paid access with it. */
    public boolean movedAccess() {
        return grants > 0;
    }
}
