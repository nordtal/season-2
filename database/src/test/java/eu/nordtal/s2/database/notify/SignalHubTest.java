package eu.nordtal.s2.database.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests the reconnect loop in {@link SignalHub} against a fake {@link Notifications}.
 *
 * It proves nothing about a real dropped socket; it pins that every wake-up and every reconnect re-reads in full.
 */
class SignalHubTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(SignalHubTest.class);

    /** Long, so a refresh counted in a short run came from a signal or a connect, never from the reconciliation. */
    private static final Duration QUIET = Duration.ofSeconds(30);

    /** Shortened from the production five seconds so several reconnects fit in one test. */
    private static final Duration BACKOFF = Duration.ofMillis(30);

    private final AtomicInteger first = new AtomicInteger();
    private final AtomicInteger second = new AtomicInteger();

    private SignalHub hub(
            final Notifications.Connector connector, final Duration reconciliation, final Duration backoff) {
        final SignalHub hub = new SignalHub(connector, "test", LOGGER, reconciliation, backoff);
        hub.on(Channel.PHASE, "the first thing", first::incrementAndGet);
        hub.on(Channel.ADMIN, "the second thing", second::incrementAndGet);
        return hub;
    }

    @Test
    void connectingReReadsEverythingBeforeWaitingForAnyNotification() throws Exception {
        final SignalHub hub = hub(channels -> FakeChannel.quiet(), QUIET, BACKOFF);

        runBriefly(hub);

        assertEquals(1, first.get(), "a connect has to re-read, since nothing sent while disconnected arrives again");
    }

    @Test
    void aQuietConnectionStillReReadsOncePerReconciliation() throws Exception {
        final SignalHub hub = hub(channels -> FakeChannel.quiet(), Duration.ofMillis(20), BACKOFF);

        runBriefly(hub);

        assertTrue(first.get() >= 4, "the reconciliation is the guarantee behind a lost signal; saw " + first.get());
    }

    @Test
    void everyRefreshRunsOnEverySignalBecauseTheChannelIsNeverInspected() throws Exception {
        final SignalHub hub = hub(channels -> FakeChannel.publishing(3), QUIET, BACKOFF);

        runBriefly(hub);

        assertEquals(4, first.get(), "one refresh for the connect plus one per notification");
        assertEquals(first.get(), second.get(), "two refreshes on one connection have to see exactly the same signals");
    }

    @Test
    void theConnectionListensOnTheUnionOfTheRegisteredChannels() throws Exception {
        final AtomicReference<Set<Channel>> asked = new AtomicReference<>();
        final SignalHub hub = hub(
                channels -> {
                    asked.set(channels);
                    return FakeChannel.quiet();
                },
                QUIET,
                BACKOFF);
        hub.on(Channel.PHASE, "a second reader of the phase", () -> {});

        runBriefly(hub);

        assertEquals(EnumSet.of(Channel.PHASE, Channel.ADMIN), asked.get());
    }

    @Test
    void aRefreshThatThrowsDoesNotTakeTheLoopOrTheOtherRefreshesDown() throws Exception {
        final SignalHub hub = new SignalHub(channels -> FakeChannel.publishing(3), "test", LOGGER, QUIET, BACKOFF);
        hub.on(Channel.PHASE, "the broken thing", () -> {
            first.incrementAndGet();
            throw new IllegalStateException("the database went away mid-refresh");
        });
        hub.on(Channel.PHASE, "the second thing", second::incrementAndGet);

        runBriefly(hub);

        assertEquals(4, second.get(), "the second refresh stopped running because the first one threw");
        assertEquals(first.get(), second.get(), "a throwing refresh must not cost itself its next signal either");
    }

    @Test
    void aLostConnectionIsReplacedAndTheNewOneReReadsAgain() throws Exception {
        final AtomicInteger opened = new AtomicInteger();
        final CountDownLatch thirdRead = new CountDownLatch(3);
        final SignalHub hub = new SignalHub(
                channels -> {
                    opened.incrementAndGet();
                    return FakeChannel.dying();
                },
                "test",
                LOGGER,
                QUIET,
                BACKOFF);
        hub.on(Channel.PHASE, "the counted thing", () -> {
            first.incrementAndGet();
            thirdRead.countDown();
        });

        final Thread thread = start(hub);
        assertTrue(thirdRead.await(30, TimeUnit.SECONDS), "the hub stopped reconnecting");
        stop(hub, thread);

        // The last connection may have opened after close() and is then never read.
        assertTrue(
                opened.get() == first.get() || opened.get() == first.get() + 1,
                "one re-read per connection: " + opened.get() + " opened, " + first.get() + " read");
    }

    @Test
    void aConnectorThatCannotConnectAtAllKeepsTryingWithoutSpinning() throws Exception {
        final AtomicInteger attempts = new AtomicInteger();
        final CountDownLatch tried = new CountDownLatch(1);
        final SignalHub hub = hub(
                channels -> {
                    attempts.incrementAndGet();
                    tried.countDown();
                    throw new SQLException("the database is not there");
                },
                QUIET,
                Duration.ofSeconds(10));

        final Thread thread = start(hub);
        assertTrue(tried.await(10, TimeUnit.SECONDS));
        Thread.sleep(200);
        stop(hub, thread);

        assertEquals(0, first.get(), "a connection that never opened has nothing to re-read");
        assertEquals(1, attempts.get(), "the backoff keeps a database outage from becoming a connection storm");
    }

    @Test
    void closingStopsTheLoopAndClosesTheOpenConnection() throws Exception {
        final FakeChannel channel = FakeChannel.quiet();
        final SignalHub hub = hub(channels -> channel, QUIET, BACKOFF);

        final Thread thread = start(hub);
        Thread.sleep(150);
        stop(hub, thread);

        assertFalse(thread.isAlive(), "close() has to end the loop, since the process shuts down behind it");
        assertTrue(channel.closed, "the dedicated connection is the process's to release on shutdown");
    }

    @Test
    void aHubWithNothingToRefreshIsRefusedRatherThanParkedForever() {
        final SignalHub hub = new SignalHub(channels -> FakeChannel.quiet(), "test", LOGGER, QUIET, BACKOFF);

        assertThrows(IllegalStateException.class, hub::start);
    }

    @Test
    void aRefreshRegisteredAfterStartIsRefusedBecauseItsChannelWouldNotBeListenedOn() {
        final SignalHub hub = hub(channels -> FakeChannel.quiet(), QUIET, BACKOFF);
        hub.start();
        try {
            assertThrows(IllegalStateException.class, () -> hub.on(Channel.UPDATE, "late", () -> {}));
        } finally {
            hub.close();
        }
    }

    private static void runBriefly(final SignalHub hub) throws InterruptedException {
        final Thread thread = start(hub);
        Thread.sleep(200);
        stop(hub, thread);
    }

    private static Thread start(final SignalHub hub) {
        final Thread thread = new Thread(hub::run, "signal-hub-test");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void stop(final SignalHub hub, final Thread thread) throws InterruptedException {
        hub.close();
        thread.join(TimeUnit.SECONDS.toMillis(10));
    }

    /** A scripted {@link Notifications}: a queue of answers, then quiet forever or an exception. */
    private static final class FakeChannel implements Notifications {

        private final Deque<Boolean> script = new ArrayDeque<>();
        private final boolean dieWhenScriptRunsOut;
        private final CountDownLatch closing = new CountDownLatch(1);
        private volatile boolean closed;

        private FakeChannel(final boolean dieWhenScriptRunsOut) {
            this.dieWhenScriptRunsOut = dieWhenScriptRunsOut;
        }

        static FakeChannel quiet() {
            return new FakeChannel(false);
        }

        static FakeChannel publishing(final int notifications) {
            final FakeChannel channel = new FakeChannel(false);
            for (int index = 0; index < notifications; index++) {
                channel.script.add(Boolean.TRUE);
            }
            return channel;
        }

        static FakeChannel dying() {
            return new FakeChannel(true);
        }

        @Override
        public boolean awaitNotification(final Duration timeout) throws SQLException {
            final Boolean next;
            synchronized (script) {
                next = script.poll();
            }
            if (next != null) {
                return next;
            }
            if (dieWhenScriptRunsOut) {
                throw new SQLException("the connection went away");
            }
            try {
                // A quiet interval, cut short the way closing a real connection under the wait cuts it short.
                if (closing.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new SQLException("the connection was closed");
                }
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted", interrupted);
            }
            return false;
        }

        @Override
        public void close() {
            closed = true;
            closing.countDown();
        }
    }
}
