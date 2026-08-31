package eu.nordtal.s2.networkcontrol.phase;

import java.sql.SQLException;
import java.time.Duration;

/**
 * One live {@code LISTEN nordtal_phase} connection, reduced to the single question the listener
 * loop asks it: <em>did anything arrive?</em>
 * <p>
 * This interface exists so that {@link PhaseListener}'s reconnect loop - which is the part with
 * actual logic in it - can be exercised without a database, and so that the pgjdbc-specific parts
 * live in exactly one class ({@link PostgresPhaseNotifications}). <b>It does not make the loop
 * testable in any meaningful sense</b>: a fake that throws on demand proves the loop reconnects
 * when told to, and proves nothing about what a real dropped TCP socket does. See
 * docs/operations.md#open-verification - that row is closed by a restart drill against a real
 * database, not by a unit test.
 * </p>
 */
public interface PhaseNotifications extends AutoCloseable {

    /**
     * Blocks until a notification arrives on the channel or the timeout runs out.
     *
     * @param timeout how long to wait; the loop uses this only to get back control periodically,
     *                since the 30-second poll is the actual guarantee
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
         * @return a connection with {@code LISTEN nordtal_phase} already issued on it
         * @throws SQLException if the connection could not be opened or the {@code LISTEN} failed
         */
        PhaseNotifications listen() throws SQLException;
    }
}
