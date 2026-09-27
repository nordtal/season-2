package eu.nordtal.s2.steward.worker.serve;

import java.sql.SQLException;
import java.time.Duration;

/**
 * One live {@code LISTEN nordtal_update} connection: has anything been announced, and is it still alive?
 *
 * An interface so the reconnect loop above it can be tested without a real dropped socket.
 */
public interface Notifications extends AutoCloseable {

    /** Opens a fresh {@code LISTEN} connection. */
    @FunctionalInterface
    interface Connector {

        /**
         * Opens the connection.
         *
         * @return a connection with {@code LISTEN nordtal_update} already issued on it
         * @throws SQLException if the connection could not be opened or the {@code LISTEN} failed
         */
        Notifications listen() throws SQLException;
    }

    /**
     * Waits for a notification.
     *
     * @param timeout how long to block, up to the server's next due piece of work
     * @return {@code true} if something was announced, {@code false} on a plain timeout
     * @throws SQLException when the connection is no longer usable, the reconnect loop's cue
     */
    boolean awaitNotification(Duration timeout) throws SQLException;

    @Override
    void close();
}
