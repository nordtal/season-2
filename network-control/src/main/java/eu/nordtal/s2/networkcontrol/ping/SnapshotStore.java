package eu.nordtal.s2.networkcontrol.ping;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.slf4j.Logger;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the last snapshot that came back, and refreshes it on a timer.
 *
 * <p><b>A failed refresh keeps the previous snapshot.</b> The numbers decorate a server list; a
 * database hiccup should cost freshness and nothing else, and blanking them would turn a ten-second
 * outage into a MOTD that says the season has no players. The failure is logged once per failure
 * and not per ping - it is not the ping path that failed.
 *
 * <p>The one caller that must not block is {@link NetworkPing}: {@link #current()} is a field read
 * and touches nothing else.
 */
public final class SnapshotStore {

    private final SnapshotDao dao;
    private final Logger logger;
    private final AtomicReference<NetworkSnapshot> current = new AtomicReference<>(NetworkSnapshot.EMPTY);

    private SnapshotStore(final SnapshotDao dao, final Logger logger) {
        this.dao = dao;
        this.logger = logger;
    }

    /**
     * @param dataSource the proxy's own pool, the same one the access directory borrows
     * @param logger     the plugin logger
     * @return a store over that pool; it owns nothing and there is nothing to close
     */
    public static SnapshotStore using(final DataSource dataSource, final Logger logger) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(logger, "logger");
        return new SnapshotStore(Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(SnapshotDao.class), logger);
    }

    /** @return the last snapshot that came back, or {@link NetworkSnapshot#EMPTY} if none ever has */
    public NetworkSnapshot current() {
        return current.get();
    }

    /**
     * Runs the query and replaces the snapshot with what it returns. Called from the proxy's
     * scheduler, never from a ping.
     */
    public void refresh() {
        try {
            final NetworkSnapshot snapshot = dao.snapshot();
            if (snapshot != null) {
                current.set(snapshot);
            }
        } catch (final RuntimeException failure) {
            // Nothing is retried and nothing is cleared: the next tick is the retry, and the
            // snapshot that is already there is better than no MOTD numbers at all.
            logger.warn("Could not refresh the MOTD snapshot; the server browser keeps showing the "
                    + "previous numbers", failure);
        }
    }
}
