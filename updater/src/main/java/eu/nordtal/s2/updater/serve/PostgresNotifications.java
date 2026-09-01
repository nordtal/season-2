package eu.nordtal.s2.updater.serve;

import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.updater.config.DatabaseSpec;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;

/**
 * The pgjdbc half of {@link Notifications}: one plain JDBC connection with
 * {@code LISTEN nordtal_update} on it, polled with {@code PGConnection#getNotifications(int)}.
 *
 * <h2>Why it is not a pooled connection</h2>
 * The same two reasons {@code network-control} gives for the phase listener. {@code LISTEN} is
 * session state, and a pool hands sessions back out; and this connection is parked inside a
 * blocking call for as long as the process runs, which is not a connection a pool can ever
 * reclaim. pgjdbc has no callback API, so a thread has to sit on it.
 *
 * <h2>The socket timeout is not optional</h2>
 * A peer that goes away without closing leaves {@code getNotifications} sitting on a dead socket
 * indefinitely - the exact failure the reconnect loop exists to recover from, and the one it would
 * never be told about. So the socket has a timeout, and every quiet wait is followed by a liveness
 * check that turns a dead connection into the exception the loop is waiting for.
 */
public final class PostgresNotifications implements Notifications {

    private static final int LIVENESS_CHECK_SECONDS = 2;

    private final Connection connection;
    private final PGConnection pg;

    private PostgresNotifications(final Connection connection) throws SQLException {
        this.connection = connection;
        this.pg = connection.unwrap(PGConnection.class);
    }

    /**
     * @param config the same credentials the pool uses - the listener reads the same database, just
     *               not through the pool
     * @return a connector that opens one dedicated {@code LISTEN} connection per call
     */
    public static Connector connector(final DatabaseSpec config) {
        return () -> {
            final Properties properties = new Properties();
            properties.setProperty("user", config.username());
            properties.setProperty("password", config.password() == null ? "" : config.password());
            properties.setProperty("socketTimeout", String.valueOf(config.queryTimeoutSeconds()));
            properties.setProperty("tcpKeepAlive", "true");
            properties.setProperty("ApplicationName", "updater-listener");

            final Connection connection = DriverManager.getConnection(config.jdbcUrl(), properties);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LISTEN " + UpdateDirectory.CHANNEL);
                }
                return new PostgresNotifications(connection);
            } catch (final SQLException failure) {
                try {
                    connection.close();
                } catch (final SQLException ignored) {
                    // Already failing; the caller retries with a new connection.
                }
                throw failure;
            }
        };
    }

    @Override
    public boolean awaitNotification(final Duration timeout) throws SQLException {
        final PGNotification[] notifications = pg.getNotifications((int) Math.max(1L, timeout.toMillis()));
        if (notifications != null && notifications.length > 0) {
            return true;
        }
        if (!connection.isValid(LIVENESS_CHECK_SECONDS)) {
            throw new SQLException("The " + UpdateDirectory.CHANNEL
                    + " listener connection is no longer valid");
        }
        return false;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (final SQLException ignored) {
            // Closing a connection we are giving up on anyway.
        }
    }
}
