package eu.nordtal.s2.proxy.ping;

import eu.nordtal.s2.common.network.NetworkSnapshot;
import eu.nordtal.s2.common.network.SnapshotDirectory;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.slf4j.Logger;

/**
 * Holds the last snapshot that came back, and refreshes it on a timer.
 *
 * A failed refresh keeps the previous snapshot; {@link #current()} is a field read for the ping path.
 */
public final class SnapshotStore {

    private final SnapshotDirectory snapshots;
    private final Logger logger;
    private final AtomicReference<NetworkSnapshot> current = new AtomicReference<>(NetworkSnapshot.EMPTY);

    private SnapshotStore(final SnapshotDirectory snapshots, final Logger logger) {
        this.snapshots = snapshots;
        this.logger = logger;
    }

    /** A store over the proxy's own pool; it owns nothing and there is nothing to close. */
    public static SnapshotStore using(final DataSource dataSource, final Logger logger) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(logger, "logger");
        return new SnapshotStore(SnapshotDirectory.using(dataSource), logger);
    }

    /** The last snapshot that came back, or {@link NetworkSnapshot#EMPTY} if none ever has. */
    public NetworkSnapshot current() {
        return Objects.requireNonNull(
                current.get(), "current is seeded with NetworkSnapshot.EMPTY and never set to null");
    }

    /** Runs the query and replaces the snapshot; called from the scheduler, never from a ping. */
    public void refresh() {
        try {
            final NetworkSnapshot snapshot = snapshots.snapshot();
            if (snapshot != null) {
                current.set(snapshot);
            }
        } catch (final RuntimeException failure) {
            // The next tick retries, and a stale snapshot beats no numbers at all.
            logger.warn(
                    "Could not refresh the MOTD snapshot; the server browser keeps showing the " + "previous numbers",
                    failure);
        }
    }
}
