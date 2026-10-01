package eu.nordtal.s2.steward.schema;

import eu.nordtal.s2.common.time.Backoff;
import eu.nordtal.s2.common.time.Waiting;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;

/**
 * Ensures exactly one steward serves, through a PostgreSQL advisory lock held for the process's life.
 *
 * {@code UpdateServer.settleOrphans()} closes every {@code RUNNING} row, which is only safe with one serve.
 */
@Slf4j
public final class ServeLock implements AutoCloseable {

    /** The ASCII bytes of {@code nordtalS}, as a signed 64-bit integer, apart from {@link RunLock}'s key. */
    private static final long KEY = 0x6E6F726474616C53L;

    /** How long to keep asking, since on a redeploy the old container still holds the lock while it shuts down. */
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    private static final Duration BETWEEN_TRIES = Duration.ofSeconds(1);

    private final Connection connection;

    private ServeLock(final Connection connection) {
        this.connection = connection;
    }

    /**
     * Takes the serve lock, waiting up to {@link #PATIENCE} for a predecessor to let go.
     *
     * @param dataSource the pool to borrow a connection from
     * @return the held lock, or empty when another {@code serve} still has it, so this process must not start
     * @throws SQLException if the database could not be asked at all
     */
    public static Optional<ServeLock> acquire(final DataSource dataSource, final Waiting waiting) throws SQLException {
        return acquire(dataSource, PATIENCE, waiting);
    }

    /** Package-visible so a test can watch the refusal without waiting half a minute. */
    static Optional<ServeLock> acquire(final DataSource dataSource, final Duration patience, final Waiting waiting)
            throws SQLException {
        final AtomicBoolean waited = new AtomicBoolean();
        try {
            return waiting.until(
                    () -> {
                        final Optional<ServeLock> held = tryOnceUnchecked(dataSource);
                        if (held.isPresent() && waited.get()) {
                            log.info("The previous steward has let the serve lock go; carrying on.");
                        } else if (held.isEmpty() && !waited.getAndSet(true)) {
                            log.info(
                                    "Another steward still holds the serve lock - almost certainly the"
                                            + " one this deployment is replacing, finishing its shutdown. Waiting"
                                            + " up to {}s.",
                                    patience.toSeconds());
                        }
                        return held;
                    },
                    patience,
                    Backoff.fixed(BETWEEN_TRIES));
        } catch (final UncheckedSqlException failure) {
            throw failure.getCause();
        }
    }

    private static Optional<ServeLock> tryOnceUnchecked(final DataSource dataSource) {
        try {
            return tryOnce(dataSource);
        } catch (final SQLException failure) {
            throw new UncheckedSqlException(failure);
        }
    }

    /** Carries a failed ask through the wait, which takes no checked exception. */
    private static final class UncheckedSqlException extends RuntimeException {
        private UncheckedSqlException(final SQLException cause) {
            super(cause);
        }

        @Override
        public synchronized SQLException getCause() {
            return (SQLException) java.util.Objects.requireNonNull(super.getCause());
        }
    }

    private static Optional<ServeLock> tryOnce(final DataSource dataSource) throws SQLException {
        final Connection connection = dataSource.getConnection();
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, KEY);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && result.getBoolean(1)) {
                    return Optional.of(new ServeLock(connection));
                }
            }
        } catch (final SQLException failure) {
            close(connection);
            throw failure;
        }
        close(connection);
        return Optional.empty();
    }

    /** Releases the lock by giving the session back. */
    @Override
    public void close() {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, KEY);
            statement.execute();
        } catch (final SQLException failure) {
            log.warn("Could not release the serve lock cleanly; it goes away with the connection", failure);
        }
        close(connection);
    }

    private static void close(final Connection connection) {
        try {
            connection.close();
        } catch (final SQLException ignored) {
            // The pool is already unhappy; nothing useful to add.
        }
    }
}
