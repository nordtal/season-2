package eu.nordtal.season.database.notify;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
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
    private final Set<Channel> channels;

    private PostgresNotifications(final Connection connection, final Set<Channel> channels) throws SQLException {
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
     */
    public static Connector connector(
            final String jdbcUrl,
            final String username,
            final String password,
            final int socketTimeoutSeconds,
            final String applicationName) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(applicationName, "applicationName");

        return channels -> {
            final Set<Channel> listenOn = Set.copyOf(channels);
            if (listenOn.isEmpty()) {
                throw new IllegalArgumentException("a connection with no channel would park forever");
            }
            final Properties properties = new Properties();
            properties.setProperty("user", username);
            properties.setProperty("password", password == null ? "" : password);
            properties.setProperty("socketTimeout", String.valueOf(socketTimeoutSeconds));
            properties.setProperty("tcpKeepAlive", "true");
            properties.setProperty("ApplicationName", applicationName);

            final Connection connection = DriverManager.getConnection(jdbcUrl, properties);
            try {
                try (Statement statement = connection.createStatement()) {
                    for (final Channel channel : listenOn) {
                        statement.execute("LISTEN " + channel.sqlName());
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
            throw new SQLException("The listening connection for "
                    + channels.stream().map(Channel::sqlName).sorted().collect(Collectors.joining(", "))
                    + " is no longer valid");
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
