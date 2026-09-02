package eu.nordtal.s2.updater.schema;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;

/**
 * "Exactly one updater is <em>serving</em>", held by PostgreSQL for the life of the process.
 *
 * <h2>What it is protecting</h2>
 * {@code UpdateServer.settleOrphans()} takes every row left {@code RUNNING} and closes it, and its
 * justification is a claim about the world: <em>"Nothing is running those rows: the only process
 * that claims one is an updater, and this one has just started."</em> That is true of one serve and
 * false of two. With two, a second one starting marks the first one's in-flight {@code APPLY} as
 * {@code FAILED}; the real updater then calls {@code finish(...)}, whose {@code WHERE status =
 * 'RUNNING'} no longer matches, and the actual report is lost. What the operator reads is "the
 * updater stopped while this request was running" about a run that was at that moment installing
 * jars.
 *
 * <p>A second serve was not hypothetical: {@code docker compose run --rm updater} inherited the
 * service's {@code command} and started one every time somebody asked for the read-only report.
 * That is fixed on its own, by giving the report a name. This is the other half - the premise
 * settleOrphans reasons from, made true by force instead of by assumption.</p>
 *
 * <h2>Why this rather than a column or a timeout</h2>
 * A {@code claimed_by} column plus a liveness check against {@code pg_stat_activity} would be more
 * precise and costs a migration, a column and a privilege. A timeout replaces a wrong assumption
 * with a guessed number - too short and a long {@code apply} is torn away from itself, too long and
 * a real orphan sits {@code RUNNING} for hours. Only {@code serve} ever writes a {@code RUNNING}
 * row ({@code claimNext} is called from nowhere else; {@code apply} deliberately writes no request
 * at all), so one serve is exactly the invariant that makes settleOrphans correct.
 *
 * <p>Session-scoped like {@link RunLock}, and for the same reason: PostgreSQL drops it when the
 * connection goes, whether that was a clean shutdown, a killed container or a redeploy. There is no
 * state to clean up after a crash - which matters most here, because the thing that would be stuck
 * is the container that fixes things.</p>
 */
@Slf4j
public final class ServeLock implements AutoCloseable {

    /**
     * The ASCII bytes of {@code nordtalS}, as a signed 64-bit integer.
     * <p>
     * Deliberately not {@link RunLock}'s key. They mean different things and are held for different
     * lengths of time: this one for the whole life of the daemon, that one for the minutes an
     * install takes. Sharing a key would make {@code serve} unable to run its own bootstrap.
     * </p>
     */
    private static final long KEY = 0x6E6F726474616C53L;

    /**
     * How long to keep asking before giving up.
     * <p>
     * Not zero, and the reason is the ordinary case rather than an exotic one: on a redeploy the
     * replacement container starts while the old one is still inside its graceful shutdown, so the
     * lock is legitimately held for a moment by a process that is on its way out. Failing straight
     * away would turn every redeploy into at least one crash-restart cycle.
     * </p>
     */
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
     * @return the held lock, or empty when another {@code serve} still has it after the wait -
     *         which means this process must not start
     * @throws SQLException if the database could not be asked at all
     */
    public static @NotNull Optional<ServeLock> acquire(final @NotNull DataSource dataSource)
            throws SQLException {
        return acquire(dataSource, PATIENCE);
    }

    /** Package-visible so a test can watch the refusal without waiting half a minute for it. */
    static @NotNull Optional<ServeLock> acquire(final @NotNull DataSource dataSource,
                                                final @NotNull Duration patience)
            throws SQLException {
        final long deadline = System.nanoTime() + patience.toNanos();
        boolean waited = false;
        while (true) {
            final Optional<ServeLock> held = tryOnce(dataSource);
            if (held.isPresent()) {
                if (waited) {
                    log.info("The previous updater has let the serve lock go; carrying on.");
                }
                return held;
            }
            if (System.nanoTime() >= deadline) {
                return Optional.empty();
            }
            if (!waited) {
                waited = true;
                log.info("Another updater still holds the serve lock - almost certainly the one this"
                        + " deployment is replacing, finishing its shutdown. Waiting up to {}s.",
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
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
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
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, KEY);
            statement.execute();
        } catch (final SQLException failure) {
            log.warn("Could not release the serve lock cleanly; it goes away with the connection",
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
