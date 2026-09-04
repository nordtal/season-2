package eu.nordtal.s2.common.notify;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * The pgjdbc half of a listener: one plain JDBC connection with a {@code LISTEN} issued for each
 * channel, polled with {@code PGConnection#getNotifications(int)}.
 *
 * <h2>Why this connection is not from the pool</h2>
 * docs/season-phases.md states it as a cost of the design rather than an implementation detail:
 * "{@code LISTEN} needs a <b>dedicated connection outside the Hikari pool</b> and a thread that
 * calls {@code PGConnection.getNotifications(timeout)}; the pgjdbc driver has no callback API."
 * A pooled connection is the wrong shape twice over - {@code LISTEN} is session state that would
 * leak to whoever borrowed it next, and this connection is parked in a blocking read for the life
 * of the process, which is precisely what a pool exists to prevent. So it is opened straight from
 * {@link DriverManager} and belongs to one thread.
 *
 * <h2>Several channels, one connection</h2>
 * One dedicated connection, one reconnect loop, one liveness check. A second channel needs none of
 * that again: {@code getNotifications} returns whatever arrived on any channel this session listens
 * to, and every listener in this repository wants the identical thing on a wake-up - re-read the
 * authoritative state, because a notification is never the state. A parallel stack of connector,
 * notifications and loop would be a few hundred lines whose only difference is a string, and a
 * second thread to keep alive.
 *
 * <p>The cost, stated plainly: a phase switch also refreshes the admin roster and an admin change
 * also re-reads the phase row. Both are one small idempotent query at moments that are rare by
 * construction.</p>
 *
 * <h2>Timeouts</h2>
 * The connection carries the same {@code socketTimeout} the caller's pool uses, so a dead peer
 * surfaces as a {@link SQLException} the reconnect loop can act on rather than as a thread parked
 * forever.
 *
 * <h2>Why the parameters are strings and ints</h2>
 * Each process describes its database in its own {@code database.yml} spec interface, and those are
 * four unrelated types with the same four fields on them. Taking the values rather than any one of
 * those interfaces is what lets the proxy, the three Paper plugins and anything later share this
 * class - the same reason {@code AccessDirectory}'s factories take a {@code DataSource}.
 */
public final class PostgresNotifications implements Notifications {

    /** How long the liveness check after a quiet interval may take before it counts as failed. */
    private static final int LIVENESS_CHECK_SECONDS = 5;

    private final Connection connection;
    private final PGConnection pg;
    private final List<String> channels;

    private PostgresNotifications(final Connection connection, final List<String> channels)
            throws SQLException {
        this.connection = connection;
        this.pg = connection.unwrap(PGConnection.class);
        this.channels = channels;
    }

    /**
     * @param jdbcUrl               the same database the caller's pool reads; the listener just
     *                              does not go through the pool
     * @param username              database user
     * @param password              database password, {@code null} treated as empty
     * @param socketTimeoutSeconds  bounds a peer that has gone away without closing. Without it
     *                              {@code getNotifications} can sit on a dead socket indefinitely,
     *                              which is the exact failure the reconnect loop exists to recover
     *                              from and would never be told about
     * @param applicationName       what this connection calls itself in {@code pg_stat_activity};
     *                              a parked connection nobody can name is one somebody eventually
     *                              kills
     * @param channels              the channels to {@code LISTEN} on, at least one
     * @return a connector that opens one dedicated connection per call
     */
    public static Connector connector(final String jdbcUrl, final String username,
                                      final String password, final int socketTimeoutSeconds,
                                      final String applicationName, final List<String> channels) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(applicationName, "applicationName");
        final List<String> listenOn = List.copyOf(Objects.requireNonNull(channels, "channels"));
        if (listenOn.isEmpty()) {
            throw new IllegalArgumentException("a listener with no channel would park forever");
        }
        for (final String channel : listenOn) {
            // The name goes into a statement unquoted - it is an identifier, not a value, so there
            // is no placeholder for it. Every caller passes a constant from Channels; this refuses
            // the day one does not.
            if (!channel.matches("[a-z][a-z0-9_]*")) {
                throw new IllegalArgumentException(
                        "not a usable LISTEN channel name: '" + channel + "'");
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
                    // Nothing useful to do: we are already failing, and the caller retries.
                }
                throw failure;
            }
        };
    }

    /**
     * {@inheritDoc}
     * <p>
     * The timeout branch is the interesting one. {@code getNotifications(timeout)} answers
     * {@code null} both when nothing was published and when the peer has gone away without closing
     * the socket - a silently dead connection is indistinguishable from a quiet one, which is the
     * whole reason docs/season-phases.md calls the poll "the real guarantee" and the {@code NOTIFY}
     * path an optimisation. So every timeout is followed by a liveness check, which turns a dead
     * connection into the {@link SQLException} the reconnect loop is waiting for instead of leaving
     * the thread parked on it forever. The check is one round trip per timeout, which is why the
     * caller passes the poll interval as the timeout rather than something shorter.
     * </p>
     */
    @Override
    public boolean awaitNotification(final Duration timeout) throws SQLException {
        final PGNotification[] notifications =
                pg.getNotifications((int) Math.max(1L, timeout.toMillis()));
        if (notifications != null && notifications.length > 0) {
            return true;
        }
        if (!connection.isValid(LIVENESS_CHECK_SECONDS)) {
            throw new SQLException("The " + String.join(", ", channels)
                    + " listener connection is no longer valid");
        }
        return false;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (final SQLException ignored) {
            // Closing a connection we are giving up on anyway; the reconnect opens a new one.
        }
    }
}
