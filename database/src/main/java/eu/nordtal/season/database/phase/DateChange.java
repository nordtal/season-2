package eu.nordtal.season.database.phase;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The result of {@link PhaseDirectory#setLaunch} or {@link PhaseDirectory#setSmpStart}.
 *
 * @param previous the instant before, null if it was not set
 * @param current the instant now, null if the date was cleared
 * @param grants the {@code access_grant} rows moved with it, which only {@code smp_start} does
 * @param accounts the Discord accounts of those rows
 */
public record DateChange(
        @Nullable Instant previous, @Nullable Instant current, int grants, int accounts) {

    public boolean unchanged() {
        return previous == null ? current == null : previous.equals(current);
    }

    public boolean movedAccess() {
        return grants > 0;
    }
}
