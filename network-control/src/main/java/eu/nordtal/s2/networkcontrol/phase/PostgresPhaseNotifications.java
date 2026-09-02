package eu.nordtal.s2.networkcontrol.phase;

import eu.nordtal.s2.networkcontrol.config.DatabaseSpec;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;

/**
 * The pgjdbc half of the phase listener: one plain JDBC connection with {@code LISTEN nordtal_phase}
 * on it, polled with {@code PGConnection#getNotifications(int)}.
 *
 * <h2>Why this connection is not from the pool</h2>
 * docs/season-phases.md states it as a cost of the design rather than an implementation detail:
 * "{@code LISTEN} needs a <b>dedicated connection outside the Hikari pool</b> and a thread that
 * calls {@code PGConnection.getNotifications(timeout)}; the pgjdbc driver has no callback API."
 * A pooled connection is the wrong shape twice over - {@code LISTEN} is session state that would
 * leak to whoever borrowed it next, and this connection is parked in a blocking read for the life
 * of the proxy, which is precisely what a pool exists to prevent. So it is opened straight from
 * {@link DriverManager} and belongs to one thread.
 *
 * <h2>The channel name is a constant, not configuration</h2>
 * It has to match the {@code pg_notify('nordtal_phase', '')} baked into {@code :common}'s
 * {@code PhaseDao#switchPhase}, and a listener quietly configured onto a different channel would
 * look exactly like a listener that works right up until a phase switch. Settled 2026-08-31
 * together with the 30-second poll.
 *
 * <h2>Timeouts</h2>
 * The connection carries the same {@code socketTimeout} the pool uses, so a dead peer surfaces as a
 * {@link SQLException} the reconnect loop can act on rather than as a thread parked forever. That
 * timeout must be longer than the wait passed to {@link #awaitNotification(Duration)} would
 * otherwise be, which is why the wait is clamped below.
 */
final class PostgresPhaseNotifications implements PhaseNotifications {

    /** Must match {@code pg_notify('nordtal_phase', '')} in :common's PhaseDao. */
    static final String CHANNEL = "nordtal_phase";

    /**
     * Must match {@code pg_notify('nordtal_admin', discord_id)} in :common's AccessDao.
     *
     * <h2>Why it rides on this connection</h2>
     * One dedicated {@code LISTEN} connection, one reconnect loop, one liveness check. A second
     * channel needs none of that again: {@code getNotifications} returns whatever arrived on any
     * channel this session listens to, and both listeners want the identical thing on a wake-up -
     * re-read the authoritative state, because a notification is never the state. A parallel stack
     * of connector, notifications, listener and watch would be ~350 lines whose only difference is
     * a string, and two loops to keep alive instead of one.
     *
     * <p>The cost, stated plainly: a phase switch also refreshes the admin roster and an admin
     * change also re-reads the phase row. Both are one small query and both are idempotent, so the
     * cost is two queries nobody asked for at moments that are rare by construction.</p>
     */
    static final String ADMIN_CHANNEL = "nordtal_admin";

    /** How long the liveness check after a quiet interval may take before it counts as failed. */
    private static final int LIVENESS_CHECK_SECONDS = 5;

    private final Connection connection;
    private final PGConnection pg;

    private PostgresPhaseNotifications(final Connection connection) throws SQLException {
        this.connection = connection;
        this.pg = connection.unwrap(PGConnection.class);
    }

    /**
     * @param config the same credentials the pool uses - the listener reads the same database
     * @return a connector that opens one dedicated {@code LISTEN} connection per call
     */
    static Connector connector(final DatabaseSpec config) {
        return () -> {
            final Properties properties = new Properties();
            properties.setProperty("user", config.username());
            properties.setProperty("password", config.password() == null ? "" : config.password());
            // Bounds a peer that has gone away without closing: without it the getNotifications
            // call below can sit on a dead socket indefinitely, which is the exact failure the
            // reconnect loop exists to recover from and would never be told about.
            properties.setProperty("socketTimeout", String.valueOf(config.queryTimeoutSeconds()));
            properties.setProperty("tcpKeepAlive", "true");
            properties.setProperty("ApplicationName", "network-control-notification-listener");

            final Connection connection = DriverManager.getConnection(config.jdbcUrl(), properties);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LISTEN " + CHANNEL);
                    statement.execute("LISTEN " + ADMIN_CHANNEL);
                }
                return new PostgresPhaseNotifications(connection);
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
        final PGNotification[] notifications = pg.getNotifications((int) Math.max(1L, timeout.toMillis()));
        if (notifications != null && notifications.length > 0) {
            return true;
        }
        if (!connection.isValid(LIVENESS_CHECK_SECONDS)) {
            throw new SQLException("The " + CHANNEL + " listener connection is no longer valid");
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
