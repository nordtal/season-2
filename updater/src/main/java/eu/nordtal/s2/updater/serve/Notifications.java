package eu.nordtal.s2.updater.serve;

import java.sql.SQLException;
import java.time.Duration;

/**
 * One live {@code LISTEN nordtal_update} connection, reduced to the single question the server
 * asks: "has anything been announced, and if not, has this connection died?"
 * <p>
 * An interface for the same reason {@code network-control}'s {@code PhaseNotifications} is one: the
 * reconnect loop above it is worth testing and a real dropped socket is not something a test can
 * produce.
 * </p>
 */
public interface Notifications extends AutoCloseable {

    /** Opens a fresh {@code LISTEN} connection. */
    @FunctionalInterface
    interface Connector {

        /**
         * @return a connection with {@code LISTEN nordtal_update} already issued on it
         * @throws SQLException if the connection could not be opened or the {@code LISTEN} failed
         */
        Notifications listen() throws SQLException;
    }

    /**
     * Waits for a notification.
     *
     * @param timeout how long to block. The server passes the time until its next piece of work is
     *                due, so a countdown ending sooner than the poll interval wakes it on time
     * @return {@code true} if something was announced, {@code false} on a plain timeout
     * @throws SQLException when the connection is no longer usable - which is the reconnect loop's
     *                      cue, and the reason a timeout is followed by a liveness check
     */
    boolean awaitNotification(Duration timeout) throws SQLException;

    @Override
    void close();
}
