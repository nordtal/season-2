package eu.nordtal.season.database.notify;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Set;

/** A live {@code LISTEN} connection that reports only whether anything arrived, not on which channel. */
public interface Notifications extends AutoCloseable {

    /**
     * Blocks until a notification arrives or the timeout ends.
     *
     * @return whether a notification arrived
     * @throws SQLException if the connection is gone, which tells the caller to reconnect
     */
    boolean awaitNotification(Duration timeout) throws SQLException;

    /** Closes the connection. It is idempotent and never throws. */
    @Override
    void close();

    /** Opens a {@code LISTEN} connection. */
    @FunctionalInterface
    interface Connector {

        /**
         * Returns a connection that listens on every channel.
         *
         * @param channels at least one
         * @throws SQLException if the connection or a {@code LISTEN} fails
         */
        Notifications listen(Set<Channel> channels) throws SQLException;
    }
}
