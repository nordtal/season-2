package eu.nordtal.s2.common.phase;

/**
 * Thrown when a season date is refused before anything is written.
 * <p>
 * The message is written for the admin who typed the command, not for a log file: both callers -
 * the Discord command and the proxy command - print it back verbatim. That is the whole reason
 * this is its own type rather than a plain {@link IllegalArgumentException}, which those callers
 * would have to treat as "something broke" and answer with a generic apology.
 * </p>
 * <p>
 * It always means <b>nothing was written</b>. A refusal that happened halfway would be worse than
 * no refusal at all, which is why every check runs before the statement rather than inside it.
 * </p>
 */
public class SeasonDateRefused extends IllegalArgumentException {

    public SeasonDateRefused(final String message) {
        super(message);
    }
}
