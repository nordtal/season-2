package eu.nordtal.s2.updater.schema;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * "Exactly one updater is moving jars right now", held by PostgreSQL.
 *
 * <h2>Why this exists at all</h2>
 * Two updaters can be alive at once, and the arrangement invites it: the daemon runs all the time,
 * and the documented bootstrap is a one-shot {@code docker compose run --rm updater apply} that an
 * operator starts by hand - most likely on exactly the day when they are also clicking the button
 * in Discord. Two processes staging into the same {@code .nordtal-staging} directory and then
 * renaming over each other is a server that ends up with half of one version and half of another.
 *
 * <h2>Why an advisory lock and not a row</h2>
 * A lock table needs releasing, and the failure mode of "releasing" is a crashed process leaving a
 * lock nobody can clear - which is precisely the situation an update is being run to get out of.
 * A session-scoped advisory lock is released by PostgreSQL when the connection goes away, whether
 * that was a clean close, a killed container or a redeploy. Nothing to clean up, ever.
 *
 * <h2>Which is why it holds its own connection</h2>
 * The lock lives on a session, so the session has to stay put. A pooled connection returned to the
 * pool mid-run would take the lock back out into general use and leak it into whatever query
 * borrowed it next. So this takes one connection out of the pool and gives it back at
 * {@link #close()}, and the pool is sized with that in mind.
 */
@Slf4j
public final class RunLock implements AutoCloseable {

    /**
     * The lock key: the ASCII bytes of {@code nordtal1}, as a signed 64-bit integer.
     * <p>
     * Advisory locks share one namespace across the whole database, so the number has to be
     * unlikely rather than convenient. Nothing else in this project takes one.
     * </p>
     */
    private static final long KEY = 0x6E6F726474616C31L;

    private final Connection connection;

    private RunLock(final Connection connection) {
        this.connection = connection;
    }

    /**
     * Takes the lock if it is free.
     *
     * @param dataSource the pool to borrow a connection from
     * @return the held lock, or empty when another updater has it - which is a thing to report,
     *         not a thing to wait for. Waiting would mean two applies queued behind each other,
     *         and the second one has a stale plan by the time it starts
     * @throws SQLException if the database could not be asked
     */
    public static @NotNull Optional<RunLock> tryAcquire(final @NotNull DataSource dataSource)
            throws SQLException {
        final Connection connection = dataSource.getConnection();
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
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
        // Unlocking explicitly rather than relying on the close: the connection goes back to a
        // pool, and a pool that reuses the session would otherwise carry the lock with it.
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, KEY);
            statement.execute();
        } catch (final SQLException failure) {
            log.warn("Could not release the updater lock cleanly; it goes away with the connection",
                    failure);
        }
        close(connection);
    }

    private static void close(final Connection connection) {
        try {
            connection.close();
        } catch (final SQLException ignored) {
            // Returning a connection to a pool that is already unhappy; nothing useful to add.
        }
    }
}
