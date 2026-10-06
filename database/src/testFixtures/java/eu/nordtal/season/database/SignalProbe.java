package eu.nordtal.season.database;

import eu.nordtal.season.database.notify.Channel;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

/** A connection listening on one channel, for a test of what a write signals. */
public final class SignalProbe implements AutoCloseable {

    private final Connection connection;
    private final PGConnection pg;

    private SignalProbe(final Connection connection) throws SQLException {
        this.connection = connection;
        this.pg = connection.unwrap(PGConnection.class);
    }

    /** Starts listening on {@code channel}; what is signalled from here on is seen. */
    public static SignalProbe on(final DataSource dataSource, final Channel channel) {
        try {
            final SignalProbe probe = new SignalProbe(dataSource.getConnection());
            try (Statement statement = probe.connection.createStatement()) {
                statement.execute("LISTEN " + channel.sqlName());
            }
            return probe;
        } catch (final SQLException exception) {
            throw new IllegalStateException("could not listen on " + channel, exception);
        }
    }

    /** Returns whether a signal arrived, waiting up to five seconds, and forgets every one that did. */
    public boolean signalled() {
        return arrived(5000);
    }

    private boolean arrived(final int millis) {
        try {
            final PGNotification[] received = pg.getNotifications(millis);
            return received != null && received.length > 0;
        } catch (final SQLException exception) {
            throw new IllegalStateException("could not read the signals", exception);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (final SQLException ignored) {
            // A test's own connection, closed at its end.
        }
    }
}
