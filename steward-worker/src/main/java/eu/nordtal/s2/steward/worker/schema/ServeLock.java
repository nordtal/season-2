package eu.nordtal.s2.steward.worker.schema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;

/**
 * Ensures exactly one steward-worker serves, through a PostgreSQL advisory lock held for the process's life.
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
    public static Optional<ServeLock> acquire(final DataSource dataSource) throws SQLException {
        return acquire(dataSource, PATIENCE);
    }

    /** Package-visible so a test can watch the refusal without waiting half a minute. */
    static Optional<ServeLock> acquire(final DataSource dataSource, final Duration patience) throws SQLException {
        final long deadline = System.nanoTime() + patience.toNanos();
        boolean waited = false;
        while (true) {
            final Optional<ServeLock> held = tryOnce(dataSource);
            if (held.isPresent()) {
                if (waited) {
                    log.info("The previous steward-worker has let the serve lock go; carrying on.");
                }
                return held;
            }
            if (System.nanoTime() >= deadline) {
                return Optional.empty();
            }
            if (!waited) {
                waited = true;
                log.info(
                        "Another steward-worker still holds the serve lock - almost certainly the"
                                + " one this deployment is replacing, finishing its shutdown. Waiting up to"
                                + " {}s.",
                        patience.toSeconds());
            }
            try {
                Thread.sleep(BETWEEN_TRIES.toMillis());
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
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
