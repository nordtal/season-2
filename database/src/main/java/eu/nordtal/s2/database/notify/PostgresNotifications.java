package eu.nordtal.s2.database.notify;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

/**
 * One plain, unpooled JDBC connection with a {@code LISTEN} per channel, polled with {@code getNotifications}.
 * A {@code socketTimeout} turns a dead peer into a {@link SQLException} for the reconnect.
 */
public final class PostgresNotifications implements Notifications {

    /** How long the liveness check after a quiet interval may take before it counts as failed. */
    private static final int LIVENESS_CHECK_SECONDS = 5;

    private final Connection connection;
    private final PGConnection pg;
    private final List<String> channels;

    private PostgresNotifications(final Connection connection, final List<String> channels) throws SQLException {
        this.connection = connection;
        this.pg = connection.unwrap(PGConnection.class);
        this.channels = channels;
    }

    /**
     * Returns a connector that opens one dedicated connection per call.
     *
     * @param password             database password, {@code null} treated as empty
     * @param socketTimeoutSeconds bounds a peer that has gone away without closing
     * @param applicationName      this connection's name in {@code pg_stat_activity}
     * @param channels             the channels to {@code LISTEN} on, at least one
     */
    public static Connector connector(
            final String jdbcUrl,
            final String username,
            final String password,
            final int socketTimeoutSeconds,
            final String applicationName,
            final List<String> channels) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(applicationName, "applicationName");
        final List<String> listenOn = List.copyOf(Objects.requireNonNull(channels, "channels"));
        if (listenOn.isEmpty()) {
            throw new IllegalArgumentException("a listener with no channel would park forever");
        }
        for (final String channel : listenOn) {
            // The name goes into the statement unquoted: an identifier has no placeholder.
            if (!channel.matches("[a-z][a-z0-9_]*")) {
                throw new IllegalArgumentException("not a usable LISTEN channel name: '" + channel + "'");
            }
        }

        return () -> {
            final Properties properties = new Properties();
            properties.setProperty("user", username);
            properties.setProperty("password", password == null ? "" : password);
            properties.setProperty("socketTimeout", String.valueOf(socketTimeoutSeconds));
            properties.setProperty("tcpKeepAlive", "true");
            properties.setProperty("ApplicationName", applicationName);

            final Connection connection = DriverManager.getConnection(jdbcUrl, properties);
            try {
                try (Statement statement = connection.createStatement()) {
                    for (final String channel : listenOn) {
                        statement.execute("LISTEN " + channel);
                    }
                }
                return new PostgresNotifications(connection, listenOn);
            } catch (final SQLException failure) {
                try {
                    connection.close();
                } catch (final SQLException ignored) {
                    // Already failing, and the caller retries.
                }
                throw failure;
            }
        };
    }

    /**
     * {@inheritDoc}
     *
     * Every timeout is followed by a liveness check, since a dead peer also answers {@code null}.
     */
    @Override
    public boolean awaitNotification(final Duration timeout) throws SQLException {
        final PGNotification[] notifications = pg.getNotifications((int) Math.max(1L, timeout.toMillis()));
        if (notifications != null && notifications.length > 0) {
            return true;
        }
        if (!connection.isValid(LIVENESS_CHECK_SECONDS)) {
            throw new SQLException("The " + String.join(", ", channels) + " listener connection is no longer valid");
        }
        return false;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (final SQLException ignored) {
            // Giving up on this connection anyway; the reconnect opens a new one.
        }
    }
}
