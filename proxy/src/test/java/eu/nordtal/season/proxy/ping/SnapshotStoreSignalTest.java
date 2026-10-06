package eu.nordtal.season.proxy.ping;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.time.ManualScheduler;
import eu.nordtal.season.database.network.NetworkSnapshot;
import eu.nordtal.season.database.network.SnapshotDirectory;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.SignalHub;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** What makes the server list numbers follow a change: the channels they wait on, and one read for a burst. */
class SnapshotStoreSignalTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnapshotStoreSignalTest.class);

    private final AtomicInteger reads = new AtomicInteger();
    private final ManualScheduler scheduler = new ManualScheduler();

    private SnapshotStore storeReading(final SnapshotDirectory directory) {
        return new SnapshotStore(directory, LOGGER);
    }

    @Test
    void aBurstOfSignalsIsOneRead() {
        final SnapshotStore store = storeReading(() -> {
            reads.incrementAndGet();
            return NetworkSnapshot.EMPTY;
        });
        final Runnable signal = store.refreshOnSignal(scheduler);

        for (int write = 0; write < 5; write++) {
            signal.run();
        }
        assertEquals(1, scheduler.pending().size(), "five signals share the one read that is waiting");
        assertEquals(0, reads.get(), "the read waits, so the signals that follow the first can join it");

        scheduler.runPending();
        assertEquals(1, reads.get());
    }

    @Test
    void aFailedReadDoesNotSilenceTheSignalsAfterIt() {
        final SnapshotStore store = storeReading(() -> {
            reads.incrementAndGet();
            throw new IllegalStateException("the database is away");
        });
        final Runnable signal = store.refreshOnSignal(scheduler);
        signal.run();
        scheduler.runPending();

        signal.run();

        assertEquals(1, scheduler.pending().size());
    }

    @Test
    void followingWaitsOnTheRoundsTheGamesAndTheSmpAsWellAsThePhase() {
        final SignalHub hub = new SignalHub(
                channels -> {
                    throw new SQLException("never started");
                },
                "test",
                LOGGER);

        storeReading(() -> NetworkSnapshot.EMPTY).follow(hub, scheduler);

        assertEquals(EnumSet.of(Channel.PHASE, Channel.HUNGER_GAMES, Channel.SMP), hub.channels());
    }
}
