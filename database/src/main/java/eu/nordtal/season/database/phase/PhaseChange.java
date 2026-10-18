package eu.nordtal.season.database.phase;

import eu.nordtal.season.common.SeasonPhase;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The result of {@link PhaseDirectory#switchPhase(SeasonPhase, eu.nordtal.season.common.id.Actor, String)}.
 *
 * @param previous the phase before, read in the statement that replaced it
 * @param at when the switch was recorded, by the database's clock
 */
public record PhaseChange(
        SeasonPhase previous, SeasonPhase current, @Nullable Instant at) {

    public boolean unchanged() {
        return previous == current;
    }
}
