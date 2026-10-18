package eu.nordtal.season.database.phase;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.messages.Refused;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * Reads the current {@link SeasonPhase} from its one row and is the only writer of that row.
 * A switch, its audit row and its {@code NOTIFY} are one SQL statement.
 */
public interface PhaseDirectory {

    /** Returns a directory that borrows {@code dataSource} and refuses past dates by {@code clock}, in its zone. */
    static PhaseDirectory using(final DataSource dataSource, final Clock clock) {
        return new JdbiPhaseDirectory(dataSource, clock);
    }

    /**
     * Returns the phase, read from the row on every call.
     *
     * @return the phase, or {@link SeasonPhase#MAINTENANCE} if the row is missing or unknown to this build
     */
    SeasonPhase currentPhase();

    /** Returns when the network opens, empty if unset. */
    Optional<Instant> launch();

    /**
     * Returns when paid access starts, which differs from {@link #launch()}.
     * Empty means no date is announced, and purchases start at {@code now()}.
     */
    Optional<Instant> smpStart();

    /**
     * Switches the phase and records who did it in one statement. Switching to the current phase is not an error.
     *
     * @param actor who caused it: the admin, or Steward for a switch no human asked for
     * @param reason free text for the admin channel, may be null
     * @throws IllegalStateException if the {@code season_phase} row does not exist
     */
    PhaseChange switchPhase(SeasonPhase phase, Actor actor, @Nullable String reason);

    /**
     * Sets or clears {@code launch}, the instant the network opens.
     *
     * @param at the instant the network opens, null clears the date
     * @throws Refused with a {@link SeasonDateRefusal} if the date is in the past or after {@link #smpStart()}
     * @throws IllegalStateException if the {@code season_phase} row does not exist
     */
    DateChange setLaunch(@Nullable Instant at, Actor actor);

    /**
     * Sets or clears {@code smp_start} and moves the paid access anchored to it as {@link PhaseDao} describes.
     * The phase is read before the write, so a racing switch shows only in the audit entry.
     *
     * @param at the instant paid access starts, null clears the date
     * @throws Refused with a {@link SeasonDateRefusal} if the date is in the past, before {@link #launch()} or in
     *     {@code SMP}
     * @throws IllegalStateException if the {@code season_phase} row does not exist
     */
    DateChange setSmpStart(@Nullable Instant at, Actor actor);
}
