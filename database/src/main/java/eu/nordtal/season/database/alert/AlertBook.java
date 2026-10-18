package eu.nordtal.season.database.alert;

import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;

/**
 * Stores admin alerts. Steward and the bot raise them, only steward routes them.
 *
 * A raise sends {@code Channel.ALERT} on commit.
 */
public interface AlertBook {

    /** Returns a book that uses {@code dataSource}. */
    static AlertBook using(final DataSource dataSource) {
        return new JdbiAlertBook(dataSource);
    }

    /** Raises {@code alert} for the module {@code raisedBy}, such as {@code discord-bot}. */
    void raise(Alert alert, String raisedBy);

    /**
     * Raises {@code alert} unless {@code source} raised one already.
     *
     * @param source what raised it, such as {@code run:42}
     * @return whether a row was written
     */
    boolean raiseOnce(String source, Alert alert, String raisedBy);

    /** Marks every unrouted alert as routed and returns them, oldest first. Steward only. */
    List<RaisedAlert> claimUnrouted();

    /** Returns at most {@code limit} alerts, newest first. Steward only. */
    List<RaisedAlert> recent(int limit);

    /** Deletes routed alerts older than {@code age}. Steward only. */
    int purge(Duration age);
}
