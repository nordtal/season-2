package eu.nordtal.s2.common.phase;

import java.time.Instant;

/**
 * What one call to {@link PhaseDirectory#setLaunch} or {@link PhaseDirectory#setSmpStart} did.
 * <p>
 * Both values come back for the same reason {@link PhaseChange} returns both phases: every caller
 * says it out loud. The two grant counts are only ever non-zero for {@code smp_start}, which is
 * the one date that owns rows other than its own - see {@link PhaseDirectory#setSmpStart}.
 * </p>
 *
 * @param previous the instant the column held before, {@code null} when it was not set
 * @param current  the instant it holds now, {@code null} when the date was cleared
 * @param grants   how many {@code access_grant} rows were moved with it
 * @param accounts how many distinct Discord accounts those rows belong to - the number a human
 *                 actually reacts to, since one person owning four stacked periods is one person
 *                 affected and not four
 */
public record DateChange(Instant previous, Instant current, int grants, int accounts) {

    /** @return whether the write asked for the value the column already held */
    public boolean unchanged() {
        return previous == null ? current == null : previous.equals(current);
    }

    /** @return whether this write moved any paid access with it */
    public boolean movedAccess() {
        return grants > 0;
    }
}
