package eu.nordtal.s2.common.notify;

import java.sql.SQLException;
import java.time.Duration;

/**
 * One live {@code LISTEN} connection, reduced to the single question the loop asks it:
 * <em>did anything arrive?</em>
 *
 * <p>It exists so {@link NotificationListener}'s reconnect loop can be exercised without a database
 * and so the pgjdbc-specific parts live in one class ({@link PostgresNotifications}). A fake proves
 * the loop reconnects when told to and nothing about a real dropped socket.
 *
 * <p>Which channel a notification arrived on is deliberately not part of this interface: every
 * listener answers a notification by re-reading the authoritative state, so a notification is never
 * trusted to say what changed.
 */
public interface Notifications extends AutoCloseable {

    /**
     * Blocks until a notification arrives on one of the channels or the timeout runs out.
     *
     * @param timeout how long to wait; the loop uses this only to get back control periodically,
     *                since the poll is the actual guarantee
     * @return {@code true} if at least one notification arrived, {@code false} on a plain timeout
     * @throws SQLException when the connection is gone - which is the signal to reconnect, and the
     *                      only way this method reports one
     */
    boolean awaitNotification(Duration timeout) throws SQLException;

    /** Closes the underlying connection. Idempotent, and never throws. */
    @Override
    void close();

    /** Opens a fresh {@code LISTEN} connection. */
    @FunctionalInterface
    interface Connector {

        /**
         * @return a connection with a {@code LISTEN} already issued for every channel
         * @throws SQLException if the connection could not be opened or a {@code LISTEN} failed
         */
        Notifications listen() throws SQLException;
    }
}
