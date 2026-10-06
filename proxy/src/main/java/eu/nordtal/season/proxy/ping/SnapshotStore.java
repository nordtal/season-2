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
 * Holds the last snapshot that came back, and refreshes it when the signal hub wakes.
 *
 * A failed refresh keeps the previous snapshot; {@link #current()} is a field read for the ping path.
 */
public final class SnapshotStore {

    /** How long a signal waits for the ones behind it, so a burst of writes is one read. */
    static final Duration SETTLE = Duration.ofSeconds(1);

    private final SnapshotDirectory snapshots;
    private final Logger logger;
    private final AtomicReference<NetworkSnapshot> current = new AtomicReference<>(NetworkSnapshot.EMPTY);

    SnapshotStore(final SnapshotDirectory snapshots, final Logger logger) {
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

    /**
     * Refreshes whenever the hub wakes on a channel that moves a number, with signals close together as one read.
     *
     * Before {@link SignalHub#start}. The SMP channel is busy, so the hub of these numbers carries no other refresh.
     */
    public void follow(final SignalHub hub, final Scheduler scheduler) {
        Objects.requireNonNull(hub, "hub");
        Objects.requireNonNull(scheduler, "scheduler");
        final Runnable signal = refreshOnSignal(scheduler);
        for (final Channel channel : new Channel[] {Channel.PHASE, Channel.HUNGER_GAMES, Channel.SMP}) {
            hub.on(channel, "the server list numbers", signal);
        }
    }

    /** Returns what a signal calls: a refresh after {@link #SETTLE}, shared by the signals of a burst. */
    Runnable refreshOnSignal(final Scheduler scheduler) {
        return new Coalescing(scheduler, SETTLE, this::refresh)::request;
    }

    /** Runs the query and replaces the snapshot; called off the hub's thread, never from a ping. */
    public void refresh() {
        try {
            final NetworkSnapshot snapshot = snapshots.snapshot();
            if (snapshot != null) {
                current.set(snapshot);
            }
        } catch (final RuntimeException failure) {
            // The hub's next wake-up retries, and a stale snapshot beats no numbers at all.
            logger.warn(
                    "Could not refresh the MOTD snapshot; the server browser keeps showing the " + "previous numbers",
                    failure);
        }
    }
}
