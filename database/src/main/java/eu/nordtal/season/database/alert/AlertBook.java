package eu.nordtal.season.database.alert;

import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;

/**
 * Every admin alert as a row: raised by steward and the bot, routed to its channels by steward alone.
 *
 * A raise is announced on {@code Channel.ALERT} when it commits.
 */
public interface AlertBook {

    /** Returns a book over a pool the caller owns. */
    static AlertBook using(final DataSource dataSource) {
        return new JdbiAlertBook(dataSource);
    }

    /** Raises one alert on behalf of {@code raisedBy}, a module name such as {@code discord-bot}. */
    void raise(Alert alert, String raisedBy);

    /**
     * Raises one alert unless {@code source} has raised one already, so a fact seen twice is told once.
     *
     * @param source what raised it, such as {@code run:42}
     * @return whether a row was written
     */
    boolean raiseOnce(String source, Alert alert, String raisedBy);

    /** Marks every alert not yet routed as routed and returns them, oldest first; steward only. */
    List<RaisedAlert> claimUnrouted();

    /** Returns the newest alerts, newest first, at most {@code limit}; steward only. */
    List<RaisedAlert> recent(int limit);

    /** Deletes routed alerts raised longer ago than {@code age}; steward only. */
    int purge(Duration age);
}
