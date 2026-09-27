package eu.nordtal.s2.steward.worker.schema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;

/**
 * Ensures exactly one steward-worker moves jars at a time, through a PostgreSQL advisory lock.
 *
 * Session-scoped, so a crash releases it; it holds its own connection out of the pool until {@link #close()}.
 */
@Slf4j
public final class RunLock implements AutoCloseable {

    /** The lock key: the ASCII bytes of {@code nordtal1}, as a signed 64-bit integer. */
    private static final long KEY = 0x6E6F726474616C31L;

    private final Connection connection;

    private RunLock(final Connection connection) {
        this.connection = connection;
    }

    /**
     * Takes the lock if it is free, never waiting, since a queued apply would run a stale plan.
     *
     * @param dataSource the pool to borrow a connection from
     * @return the held lock, or empty when another worker has it
     * @throws SQLException if the database could not be asked
     */
    public static Optional<RunLock> tryAcquire(final DataSource dataSource) throws SQLException {
        final Connection connection = dataSource.getConnection();
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, KEY);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && result.getBoolean(1)) {
                    return Optional.of(new RunLock(connection));
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
        // Unlocked explicitly, since the pool could reuse the session with the lock still held.
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, KEY);
            statement.execute();
        } catch (final SQLException failure) {
            log.warn(
                    "Could not release the steward-worker lock cleanly; it goes away with the" + " connection",
                    failure);
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
