package eu.nordtal.s2.database.phase;

/**
 * Thrown when a season date is refused before anything is written.
 * Its message is for the admin who typed the command, and both callers print it verbatim.
 */
public class SeasonDateRefused extends IllegalArgumentException {

    public SeasonDateRefused(final String message) {
        super(message);
    }
}
