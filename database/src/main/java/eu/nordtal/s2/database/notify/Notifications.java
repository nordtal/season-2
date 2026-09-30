package eu.nordtal.s2.database.notify;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Set;

/** One live {@code LISTEN} connection, reduced to whether anything arrived; the channel is not reported. */
public interface Notifications extends AutoCloseable {

    /**
     * Blocks until a notification arrives on one of the channels or the timeout runs out.
     *
     * @return {@code true} if at least one notification arrived, {@code false} on a plain timeout
     * @throws SQLException when the connection is gone, which is the only signal to reconnect
     */
    boolean awaitNotification(Duration timeout) throws SQLException;

    /** Closes the underlying connection; idempotent, and never throws. */
    @Override
    void close();

    /** Opens a fresh {@code LISTEN} connection. */
    @FunctionalInterface
    interface Connector {

        /**
         * Returns a connection with a {@code LISTEN} already issued for every channel.
         *
         * @param channels at least one
         * @throws SQLException if the connection could not be opened or a {@code LISTEN} failed
         */
        Notifications listen(Set<Channel> channels) throws SQLException;
    }
}
