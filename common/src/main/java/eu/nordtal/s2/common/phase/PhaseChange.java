package eu.nordtal.s2.common.phase;

import eu.nordtal.s2.common.SeasonPhase;

import java.time.Instant;

/**
 * What one call to {@link PhaseDirectory#switchPhase(SeasonPhase, String, String)} did.
 * <p>
 * The previous value comes back with the new one because every caller wants to say it out loud -
 * the Discord confirmation, the admin channel line and the proxy's command feedback all read
 * "{@code PRE_EVENT} to {@code START_EVENT}" rather than just the destination.
 * </p>
 *
 * @param previous the phase the row held before, read in the same statement that replaced it
 * @param current  the phase the row holds now
 * @param at       when the switch was recorded, from the database's clock and not the JVM's
 */
public record PhaseChange(SeasonPhase previous, SeasonPhase current, Instant at) {

    /** @return whether the switch asked for the phase that was already current */
    public boolean unchanged() {
        return previous == current;
    }
}
