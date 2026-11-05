package eu.nordtal.season.database.season;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.messages.Refused;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * Reads the launch date, maintenance and the game mode from their one row and is the only writer of that row.
 * Each write, its journal entry and its {@code NOTIFY} are one transaction.
 */
public interface SeasonDirectory {

    /** Returns a directory that borrows {@code dataSource} and refuses past dates by {@code clock}. */
    static SeasonDirectory using(final DataSource dataSource, final Clock clock) {
        return new JdbiSeasonDirectory(dataSource, clock);
    }

    /** Returns the state, read from the row on every call, or {@link SeasonState#UNREAD} if the row is missing. */
    SeasonState state();

    /** Returns how many live access grants move with the launch date, which a change of it would move. */
    int anchoredGrants();

    /** Returns the last time maintenance was turned on, empty if the journal holds none. */
    Optional<MaintenanceSpan> lastMaintenance();

    /**
     * Sets the launch date and moves the access anchored to the date before it by the same time.
     *
     * @throws Refused with a {@link SeasonRefusal} if {@code at} has passed
     */
    SeasonChange setLaunch(Instant at, Actor actor, @Nullable String reason);

    /**
     * Sets the launch date to now, which opens the season and starts the access anchored to the date.
     *
     * @throws Refused with a {@link SeasonRefusal} if the season is already launched
     */
    SeasonChange launchNow(Actor actor, @Nullable String reason);

    /**
     * Removes the launch date of a launched season, which closes it. Access keeps running.
     *
     * @throws Refused with a {@link SeasonRefusal} if the season is not launched
     */
    SeasonChange closeSeason(Actor actor, @Nullable String reason);

    /** Turns maintenance on or off. Asking for the current value changes and journals nothing. */
    SeasonChange setMaintenance(boolean on, Actor actor, @Nullable String reason);

    /** Makes {@code mode} the active game mode. Asking for the current one changes and journals nothing. */
    SeasonChange setMode(SeasonMode mode, Actor actor, @Nullable String reason);
}
