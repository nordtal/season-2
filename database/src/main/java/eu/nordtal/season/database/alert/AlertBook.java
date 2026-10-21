package eu.nordtal.season.database.alert;

import eu.nordtal.season.common.id.Actor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Writes alerts as journal entries with a level and reads them back.
 * Steward and the bot raise them, only steward routes them. A raise sends {@code Channel.ALERT} on commit.
 */
public interface AlertBook {

    /** Returns a book that uses {@code dataSource}. */
    static AlertBook using(final DataSource dataSource) {
        return new JdbiAlertBook(dataSource);
    }

    /** Raises {@code alert} as {@code actor} and returns its entry's id. */
    UUID raise(Alert alert, Actor actor);

    /**
     * Raises {@code alert} unless an alert of its action was raised for its run or its bank payment already.
     *
     * @return whether an entry was written
     */
    boolean raiseOnce(Alert alert, Actor actor);

    /** Marks every unrouted alert as routed and returns them, oldest first. Steward only. */
    List<RaisedAlert> claimUnrouted();

    /** Returns at most {@code limit} alerts, newest first. */
    List<RaisedAlert> recent(int limit);

    /** Returns the alert of the entry {@code id}, empty for an entry that is no alert. */
    Optional<RaisedAlert> alert(UUID id);
}
