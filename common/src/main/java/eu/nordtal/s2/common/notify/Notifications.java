package eu.nordtal.s2.common.notify;

import java.sql.SQLException;
import java.time.Duration;

/**
 * One live {@code LISTEN} connection, reduced to the single question the loop asks it:
 * <em>did anything arrive?</em>
 *
 * <p>This interface exists so that {@link NotificationListener}'s reconnect loop - which is the part
 * with actual logic in it - can be exercised without a database, and so that the pgjdbc-specific
 * parts live in exactly one class ({@link PostgresNotifications}). <b>It does not make the loop
 * testable in any meaningful sense</b>: a fake that throws on demand proves the loop reconnects when
 * told to, and proves nothing about what a real dropped TCP socket does. See
 * docs/state-of-play.md#the-unverified-assumptions - that row is closed by a restart drill against a
 * real database, not by a unit test.</p>
 *
 * <p>Which channel a notification arrived on is deliberately not part of this interface. Every
 * listener in this repository answers a notification the same way - by re-reading the authoritative
 * state - so routing by channel would mean trusting the notification to say what changed, which is
 * the one thing the design never does. A connection carrying several channels is therefore cheaper
 * than several connections and loses nothing.</p>
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
