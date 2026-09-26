package eu.nordtal.s2.commands.phase;

import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.phase.DateChange;
import eu.nordtal.s2.common.phase.PhaseChange;
import eu.nordtal.s2.common.phase.PhaseDirectory;
import java.time.Instant;
import java.util.Optional;

/**
 * The back half of {@code /phase}: everything the two processes that answer it do differently.
 *
 * This interface is thin because most of {@code /phase} is already shared. The write goes through
 * {@link PhaseDirectory#switchPhase}, which is in {@code :common} and which <b>both</b>
 * implementations call - one SQL statement writing the row, the {@code audit_log} entry
 * and the {@code NOTIFY} together. What is left here is per-process: {@link #observation()}, because
 * the proxy holds the phase in its {@code PhaseWatch} and answers {@code /phase} from memory
 * <em>on purpose</em> (this is the command somebody runs while the network is misbehaving, and it
 * should still say something useful when the database cannot be reached, while the bot holds
 * nothing and reads the row); {@link #afterWrite()}, because a process that caches the phase has to
 * re-read it rather than wait for its own notification to come back around, so that the reply and
 * the log agree (the bot caches nothing and does nothing here); {@link #recordSwitch} and
 * {@link #recordDate}, where a process files admin actions (the bot writes a mention into the admin
 * channel, the proxy writes a {@code WARN} line, and neither is a message key because neither is
 * read by the person who typed the command); and {@link #async}, Velocity's scheduler and the bot's
 * worker executor, both because the calling thread is one nothing may block.
 */
public interface PhaseEffects extends CommandEffects {

    /**
     * What a process already knows without asking the database.
     *
     * @param phase    the phase last observed - or the safe fallback, if none ever was
     * @param everRead whether that is an observation or the fallback. The difference is worth a
     *                 different sentence: "the network is in X" and "the network has not been
     *                 readable, so it is being treated as X" are not the same statement
     * @param launch   the announced opening, if this process holds one
     */
    record Observation(SeasonPhase phase, boolean everRead, Instant launch) {}

    /** The row, for everything that has to be read or written for real. */
    PhaseDirectory phases();

    /** @return what this process already knows, or empty when it caches nothing */
    Optional<Observation> observation();

    /** Re-read a cached phase after a write of our own. A process that caches nothing does nothing. */
    void afterWrite();

    /** File a phase switch where this process files admin actions. */
    void recordSwitch(NordtalUser who, PhaseChange change);

    /**
     * File a season-date change where this process files admin actions.
     *
     * @param launch {@code true} for the opening, {@code false} for the start of paid access
     */
    void recordDate(NordtalUser who, boolean launch, DateChange change);
}
