package eu.nordtal.s2.common.phase;

import eu.nordtal.s2.common.SeasonPhase;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * Reads the current {@link SeasonPhase} from its one row, and is the only way that row is written.
 * A switch, its audit row and its {@code NOTIFY} are one SQL statement.
 */
public interface PhaseDirectory {

    /** Returns a directory over a connection pool the caller owns. */
    static PhaseDirectory using(final DataSource dataSource) {
        return new JdbiPhaseDirectory(dataSource);
    }

    /**
     * Returns the phase right now, read from the row every time it is asked; database failures propagate.
     *
     * @return the current phase, or {@link SeasonPhase#MAINTENANCE} if the row is missing or unknown to this build
     */
    SeasonPhase currentPhase();

    /** Returns when the network opens; empty means unset, and database failures propagate. */
    Optional<Instant> launch();

    /**
     * Returns when paid access starts running, which is not {@link #launch()}; database failures propagate.
     * Empty means no date is announced, and purchases start at {@code now()}.
     */
    Optional<Instant> smpStart();

    /**
     * Switches the phase and records who did it, in one statement; switching to the current phase is not an error.
     *
     * @param actor  the Discord id of the admin who caused it, or {@code null} for a switch no human asked for
     * @param reason free text for whoever reads the admin channel later, may be {@code null}
     * @throws IllegalStateException if the {@code season_phase} row does not exist
     */
    PhaseChange switchPhase(SeasonPhase phase, @Nullable String actor, @Nullable String reason);

    /**
     * Sets or clears {@code launch}, the instant the network opens.
     *
     * @param at    the instant the network opens, or {@code null} for no date announced
     * @param actor the Discord id of the admin who asked for it, or {@code null} for the console
     * @return what the column held before and holds now
     * @throws SeasonDateRefused     if the date is in the past or after {@link #smpStart()}
     * @throws IllegalStateException if the {@code season_phase} row does not exist
     */
    DateChange setLaunch(@Nullable Instant at, @Nullable String actor);

    /**
     * Sets or clears {@code smp_start}, and moves the paid access anchored to it as {@link PhaseDao} describes.
     * The phase is read just before the write, so a racing switch shows only in the audit entry.
     *
     * @param at    the instant paid access starts running, or {@code null} to clear the date
     * @param actor the Discord id of the admin who asked for it, or {@code null} for the console
     * @return what the column held before and holds now, and how much access moved with it
     * @throws SeasonDateRefused     if the date is in the past, before {@link #launch()}, or the phase is {@code SMP}
     * @throws IllegalStateException if the {@code season_phase} row does not exist
     */
    DateChange setSmpStart(@Nullable Instant at, @Nullable String actor);
}
