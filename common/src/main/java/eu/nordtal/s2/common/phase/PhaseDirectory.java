package eu.nordtal.s2.common.phase;

import eu.nordtal.s2.common.SeasonPhase;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * Reads the current {@link SeasonPhase} from its one row, and is the only way that row is written.
 *
 * A switch, its audit row and its {@code NOTIFY} are one SQL statement. One instance per process, over a
 * pool somebody else owns.
 */
public interface PhaseDirectory {

    /**
     * Reads the phase over a connection pool the caller owns.
     *
     * @param dataSource the pool - the same one {@code AccessDirectory.using(DataSource)} is given
     * @return a directory over that pool
     */
    static PhaseDirectory using(final DataSource dataSource) {
        return new JdbiPhaseDirectory(dataSource);
    }

    /**
     * The phase right now, read from the row every time it is asked.
     *
     * <b>Database failures propagate</b> rather than being folded into
     * {@link SeasonPhase#MAINTENANCE}: a caller has to tell "the season is in maintenance" from "I
     * could not reach the database", because the proxy keeps using the last known phase and assumes
     * {@code MAINTENANCE} only when it has never read one.
     *
     * @return the current phase; {@link SeasonPhase#MAINTENANCE} if the row is somehow missing or
     *         holds a value this build does not know - an unreadable phase must never be more
     *         permissive than the real one
     */
    SeasonPhase currentPhase();

    /**
     * Returns when the network opens, from the row {@link #currentPhase()} reads.
     *
     * Nothing acts on it and it is not cleared by a switch. Database failures propagate; empty means unset.
     */
    Optional<Instant> launch();

    /**
     * When paid access starts running, from the same single row {@link #currentPhase()} reads.
     *
     * <b>This is not {@link #launch()}</b>: the network opens into {@code PRE_EVENT}, where
     * nobody needs access, so the day the browser counts down to and the day a purchase starts
     * running are separated by a whole event. It has to be set in advance because a grant's window
     * is computed when it is bought and never rewritten afterwards.
     *
     * Empty means no date has been announced; purchases still go through and start at
     * {@code now()}. <b>Database failures propagate</b>, as for {@link #currentPhase()}.
     *
     * @return the instant paid access starts running, or empty when none is set
     */
    Optional<Instant> smpStart();

    /**
     * Switches the phase and records who did it, in one statement.
     *
     * Switching to the current phase is not an error; {@link PhaseChange#unchanged()} reports it.
     *
     * @param actor  the Discord id of the admin who caused it, or {@code null} for a switch no human asked for
     * @param reason free text for whoever reads the admin channel later, may be {@code null}
     * @throws IllegalStateException if the {@code season_phase} row does not exist
     */
    PhaseChange switchPhase(SeasonPhase phase, @Nullable String actor, @Nullable String reason);

    /**
     * Sets or clears {@code launch}, the instant the network opens.
     *
     * Refused without a write when the date is in the past or after {@link #smpStart()}.
     *
     * @param at    the instant the network opens, or {@code null} for no date announced
     * @param actor the Discord id of the admin who asked for it, or {@code null} for the console
     * @return what the column held before and holds now
     * @throws SeasonDateRefused     if the date is in the past or after {@link #smpStart()}
     * @throws IllegalStateException if the {@code season_phase} row does not exist
     */
    DateChange setLaunch(@Nullable Instant at, @Nullable String actor);

    /**
     * Sets or clears {@code smp_start}, <b>and moves the paid access anchored to it</b>.
     *
     * A grant's window is computed from this instant when it is bought, weeks in advance, so
     * moving the date without moving those rows would leave everything sold running from a day that
     * no longer means anything. Per Discord account, the earliest live grant is placed on the new
     * date and the rest keep their distance from it. Setting the date for the first time moves every
     * live grant, which is the case this exists for; <b>clearing moves nothing</b>, because there
     * would be no instant left to anchor to.
     *
     * Refused, with {@link SeasonDateRefused} and no write: a date in the past, a date before
     * {@link #launch()} when that is set, and any change once the phase is {@code SMP}, where paid
     * time is genuinely being consumed. The phase is read just before the write rather than inside
     * it, so a switch racing this call by milliseconds is not caught and the audit entry is what
     * makes it visible.
     *
     * @param at    the instant paid access starts running, or {@code null} to clear the date
     * @param actor the Discord id of the admin who asked for it, or {@code null} for the console
     * @return what the column held before and holds now, and how much access moved with it
     * @throws SeasonDateRefused     if the date is in the past, before {@link #launch()}, or the
     *                               phase is already {@code SMP}
     * @throws IllegalStateException if the {@code season_phase} row does not exist
     */
    DateChange setSmpStart(@Nullable Instant at, @Nullable String actor);
}
