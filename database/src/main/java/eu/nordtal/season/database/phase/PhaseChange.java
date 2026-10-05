package eu.nordtal.season.database.phase;

import eu.nordtal.season.common.SeasonPhase;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What one call to {@link PhaseDirectory#switchPhase(SeasonPhase, eu.nordtal.season.common.id.Actor, String)} did.
 *
 * @param previous the phase the row held before, read in the same statement that replaced it
 * @param current  the phase the row holds now
 * @param at       when the switch was recorded, from the database's clock and not the JVM's
 */
public record PhaseChange(
        SeasonPhase previous, SeasonPhase current, @Nullable Instant at) {

    /** Returns whether the switch asked for the phase that was already current. */
    public boolean unchanged() {
        return previous == current;
    }
}
