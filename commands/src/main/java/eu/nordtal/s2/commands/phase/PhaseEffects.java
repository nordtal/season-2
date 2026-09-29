package eu.nordtal.s2.commands.phase;

import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.phase.DateChange;
import eu.nordtal.s2.common.phase.PhaseChange;
import eu.nordtal.s2.common.phase.PhaseDirectory;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The back half of {@code /phase}: what the two processes that answer it do differently.
 *
 * The proxy answers from memory so {@code /phase} works without a database; the bot caches nothing and reads the row.
 */
public interface PhaseEffects extends CommandEffects {

    /**
     * What a process already knows without asking the database.
     *
     * @param phase    the phase last observed, or the safe fallback if none ever was
     * @param everRead whether that is an observation or the fallback, which the reply words differently
     * @param launch   the announced opening, if this process holds one
     */
    record Observation(
            SeasonPhase phase, boolean everRead, @Nullable Instant launch) {}

    /** The row, for everything that has to be read or written for real. */
    PhaseDirectory phases();

    /** Returns what this process already knows, or empty when it caches nothing. */
    Optional<Observation> observation();

    /** Re-reads a cached phase after this process's own write; a process that caches nothing does nothing. */
    void afterWrite();

    /** Files a phase switch where this process files admin actions. */
    void recordSwitch(NordtalUser who, PhaseChange change);

    /**
     * Files a season-date change where this process files admin actions.
     *
     * @param launch {@code true} for the opening, {@code false} for the start of paid access
     */
    void recordDate(NordtalUser who, boolean launch, DateChange change);
}
