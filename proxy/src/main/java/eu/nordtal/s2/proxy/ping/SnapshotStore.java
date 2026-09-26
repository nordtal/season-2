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
 * <b>A failed refresh keeps the previous snapshot.</b> The numbers decorate a server list; a
 * database hiccup should cost freshness and nothing else, and blanking them would turn a ten-second
 * outage into a MOTD that says the season has no players. The failure is logged once per failure
 * and not per ping - it is not the ping path that failed.
 *
 * The one caller that must not block is {@link NetworkPing}: {@link #current()} is a field read
 * and touches nothing else.
 *
 * The query itself lives in {@code :common}, because the Discord bot renders the same numbers
 * into a channel name. What stays here is the cache and the failure rule - the bot keeps its own,
 * with a different tolerance for staleness.
 */
public final class SnapshotStore {

    private final SnapshotDirectory snapshots;
    private final Logger logger;
    private final AtomicReference<NetworkSnapshot> current = new AtomicReference<>(NetworkSnapshot.EMPTY);

    private SnapshotStore(final SnapshotDirectory snapshots, final Logger logger) {
        this.snapshots = snapshots;
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
        return new SnapshotStore(SnapshotDirectory.using(dataSource), logger);
    }

    /** @return the last snapshot that came back, or {@link NetworkSnapshot#EMPTY} if none ever has */
    public NetworkSnapshot current() {
        return Objects.requireNonNull(
                current.get(), "current is seeded with NetworkSnapshot.EMPTY and never set to null");
    }

    /**
     * Runs the query and replaces the snapshot with what it returns.
     *
     * Called from the proxy's scheduler, never from a ping.
     */
    public void refresh() {
        try {
            final NetworkSnapshot snapshot = snapshots.snapshot();
            if (snapshot != null) {
                current.set(snapshot);
            }
        } catch (final RuntimeException failure) {
            // Nothing is retried or cleared: the next tick retries, and a stale snapshot beats no numbers at all.
            logger.warn(
                    "Could not refresh the MOTD snapshot; the server browser keeps showing the " + "previous numbers",
                    failure);
        }
    }
}
