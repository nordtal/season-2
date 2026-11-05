package eu.nordtal.season.database.season;

import eu.nordtal.season.common.season.SeasonState;

/**
 * The result of a write of {@link SeasonDirectory}.
 *
 * @param previous the state before, read in the statement that replaced it
 * @param grants the {@code access_grant} rows moved with the launch date
 * @param accounts the Discord accounts of those rows
 */
public record SeasonChange(SeasonState previous, SeasonState current, int grants, int accounts) {

    public boolean unchanged() {
        return previous.equals(current);
    }
}
