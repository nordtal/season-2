package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.steward.worker.config.DatabaseSpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

/**
 * The pgjdbc half of {@link Notifications}: one dedicated, unpooled connection polled with {@code getNotifications}.
 *
 * The socket has a timeout and each quiet wait a liveness check, so a dead peer becomes an exception.
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
     * Builds the connector.
     *
     * @param config the same credentials the pool uses
     * @return a connector that opens one dedicated {@code LISTEN} connection per call
     */
    public static Connector connector(final DatabaseSpec config) {
        return () -> {
            final Properties properties = new Properties();
            properties.setProperty("user", config.username());
            properties.setProperty("password", config.password() == null ? "" : config.password());
            properties.setProperty("socketTimeout", String.valueOf(config.queryTimeoutSeconds()));
            properties.setProperty("tcpKeepAlive", "true");
            properties.setProperty("ApplicationName", "steward-worker-listener");

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
            throw new SQLException("The " + UpdateDirectory.CHANNEL + " listener connection is no longer valid");
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
