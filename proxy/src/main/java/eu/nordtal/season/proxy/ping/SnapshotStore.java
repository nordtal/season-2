package eu.nordtal.season.proxy.ping;

import eu.nordtal.season.common.time.Coalescing;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.network.NetworkSnapshot;
import eu.nordtal.season.database.network.SnapshotDirectory;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.SignalHub;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.slf4j.Logger;

/**
 * Holds the last {@link NetworkSnapshot} and refreshes it on a signal of the hub.
 * A failed refresh keeps the previous snapshot.
 */
public final class SnapshotStore {

    /** The time that a signal waits for further signals. Signals within it cause one refresh. */
    static final Duration SETTLE = Duration.ofSeconds(1);

    private final SnapshotDirectory snapshots;
    private final Logger logger;
    private final AtomicReference<NetworkSnapshot> current = new AtomicReference<>(NetworkSnapshot.EMPTY);

    SnapshotStore(final SnapshotDirectory snapshots, final Logger logger) {
        this.snapshots = snapshots;
        this.logger = logger;
    }

    /** Creates a store that uses the pool of the proxy. */
    public static SnapshotStore using(final DataSource dataSource, final Logger logger) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(logger, "logger");
        return new SnapshotStore(SnapshotDirectory.using(dataSource), logger);
    }

    /** Returns the last snapshot, or {@link NetworkSnapshot#EMPTY} before the first refresh. */
    public NetworkSnapshot current() {
        return Objects.requireNonNull(
                current.get(), "current is seeded with NetworkSnapshot.EMPTY and never set to null");
    }

    /** Refreshes on each signal of a channel that changes a number. Call it before {@link SignalHub#start}. */
    public void follow(final SignalHub hub, final Scheduler scheduler) {
        Objects.requireNonNull(hub, "hub");
        Objects.requireNonNull(scheduler, "scheduler");
        final Runnable signal = refreshOnSignal(scheduler);
        for (final Channel channel : new Channel[] {Channel.PHASE, Channel.HUNGER_GAMES, Channel.SMP}) {
            hub.on(channel, "the server list numbers", signal);
        }
    }

    /** Returns the action of a signal: a refresh after {@link #SETTLE}. */
    Runnable refreshOnSignal(final Scheduler scheduler) {
        return new Coalescing(scheduler, SETTLE, this::refresh)::request;
    }

    /** Reads the snapshot from the database. */
    public void refresh() {
        try {
            final NetworkSnapshot snapshot = snapshots.snapshot();
            if (snapshot != null) {
                current.set(snapshot);
            }
        } catch (final RuntimeException failure) {
            logger.warn(
                    "Could not refresh the MOTD snapshot; the server browser keeps showing the " + "previous numbers",
                    failure);
        }
    }
}
