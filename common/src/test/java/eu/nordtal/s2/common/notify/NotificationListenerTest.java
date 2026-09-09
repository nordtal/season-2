package eu.nordtal.s2.common.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reconnect loop in {@link NotificationListener}, against a fake {@link Notifications}.
 *
 * <p><b>None of this proves anything about a real dropped socket.</b> A fake that throws on demand
 * is not a network partition, and {@code getNotifications} answering {@code null} forever on a
 * silently dead connection cannot be reproduced in a JVM at all - that needs a drill against a real
 * PostgreSQL with the connection killed underneath the process.
 *
 * <p>What these tests do pin is the rule that is a coding mistake rather than an environmental one:
 * every reconnect re-reads unconditionally.
 */
class NotificationListenerTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationListenerTest.class);
    private static final Duration WAIT = Duration.ofMillis(20);

    /** Shortened from the production five seconds so several reconnects fit in one test. */
    private static final Duration BACKOFF = Duration.ofMillis(30);

    private final AtomicInteger first = new AtomicInteger();
    private final AtomicInteger second = new AtomicInteger();

    private List<NotificationListener.Refresh> two() {
        return List.of(new NotificationListener.Refresh("the first thing", first::incrementAndGet),
                new NotificationListener.Refresh("the second thing", second::incrementAndGet));
    }

    @Test
    void connectingReReadsEverythingBeforeWaitingForAnyNotification() throws Exception {
        // A channel that never publishes anything at all. If the listener only refreshed on a
        // notification, this would refresh zero times - and a change made while the process was
        // disconnected would be invisible until the next poll.
        final FakeChannel quiet = FakeChannel.quiet();
        final NotificationListener listener =
                new NotificationListener(() -> quiet, "test", two(), LOGGER, WAIT, BACKOFF);

        runBriefly(listener);

        assertTrue(first.get() >= 1,
                "notifications are lost while a process is disconnected, so a connect has to re-read"
                        + " whether or not anything arrives afterwards");
    }

    @Test
    void aNotificationTriggersAnotherRefresh() throws Exception {
        final FakeChannel channel = FakeChannel.publishing(3);
        final NotificationListener listener =
                new NotificationListener(() -> channel, "test", two(), LOGGER, WAIT, BACKOFF);

        runBriefly(listener);

        assertTrue(first.get() >= 4,
                "one refresh for the connect plus one per notification; saw " + first.get());
    }

    @Test
    @DisplayName("every refresh runs on every signal, because the channel is never inspected")
    void allRefreshesRideTheSameSignals() throws Exception {
        // The reason several channels share one connection: both halves want the identical thing on
        // a wake-up.
        final NotificationListener listener = new NotificationListener(
                () -> FakeChannel.publishing(3), "test", two(), LOGGER, WAIT, BACKOFF);

        runBriefly(listener);

        assertEquals(first.get(), second.get(),
                "two refreshes on one connection have to see exactly the same signals");
        assertTrue(first.get() >= 4, "one per connect plus one per notification; saw " + first.get());
    }

    @Test
    @DisplayName("a refresh that throws does not take the loop, or the other refreshes, down")
    void aBrokenRefreshIsContainedAndRetried() throws Exception {
        // A failure in either refresh must not cost the other its propagation.
        final NotificationListener listener = new NotificationListener(
                () -> FakeChannel.publishing(3), "test",
                List.of(new NotificationListener.Refresh("the broken thing", () -> {
                            first.incrementAndGet();
                            throw new IllegalStateException("the database went away mid-refresh");
                        }),
                        new NotificationListener.Refresh("the second thing", second::incrementAndGet)),
                LOGGER, WAIT, BACKOFF);

        runBriefly(listener);

        assertTrue(second.get() >= 4,
                "the second refresh stopped running because the first one threw; saw " + second.get());
        assertEquals(first.get(), second.get(),
                "a throwing refresh must not cost itself its next signal either");
    }

    @Test
    void aLostConnectionIsReplacedAndTheNewOneReReadsAgain() throws Exception {
        // Three connections in a row, each dying on its first wait: every one has to re-read on the
        // way in, because a change in the gap is never announced again.
        final AtomicInteger opened = new AtomicInteger();
        final CountDownLatch thirdOpened = new CountDownLatch(3);
        final NotificationListener listener = new NotificationListener(() -> {
            opened.incrementAndGet();
            thirdOpened.countDown();
            return FakeChannel.dying();
        }, "test", two(), LOGGER, WAIT, BACKOFF);

        final Thread thread = start(listener);
        assertTrue(thirdOpened.await(30, TimeUnit.SECONDS), "the listener stopped reconnecting");
        stop(listener, thread);

        assertTrue(opened.get() >= 3, "reconnected " + opened.get() + " times");
        assertEquals(opened.get(), first.get(),
                "exactly one unconditional re-read per connection, no more and no fewer");
    }

    @Test
    void aConnectorThatCannotConnectAtAllKeepsTryingWithoutSpinning() throws Exception {
        // A long backoff for this one, so the assertion below is about the backoff being honoured
        // rather than about how fast the machine running the test happens to be.
        final Duration slowBackoff = Duration.ofSeconds(10);
        final AtomicInteger attempts = new AtomicInteger();
        final CountDownLatch tried = new CountDownLatch(1);
        final NotificationListener listener = new NotificationListener(() -> {
            attempts.incrementAndGet();
            tried.countDown();
            throw new SQLException("the database is not there");
        }, "test", two(), LOGGER, WAIT, slowBackoff);

        final Thread thread = start(listener);
        assertTrue(tried.await(10, TimeUnit.SECONDS));
        Thread.sleep(200);
        stop(listener, thread);

        assertEquals(0, first.get(), "a connection that never opened has nothing to re-read");
        assertEquals(1, attempts.get(),
                "the backoff is what stops a database outage from becoming a connection-attempt storm");
    }

    @Test
    void closingStopsTheLoopAndClosesTheOpenConnection() throws Exception {
        final FakeChannel channel = FakeChannel.quiet();
        final NotificationListener listener =
                new NotificationListener(() -> channel, "test", two(), LOGGER, WAIT, BACKOFF);

        final Thread thread = start(listener);
        Thread.sleep(150);
        stop(listener, thread);

        assertFalse(thread.isAlive(),
                "close() has to end the loop, not merely ask it to - the process shuts down behind it");
        assertTrue(channel.closed, "the dedicated connection is the process's to release on shutdown");
    }

    @Test
    @DisplayName("a listener with nothing to refresh is refused rather than parked forever")
    void refreshesAreNotOptional() {
        assertThrows(IllegalArgumentException.class, () -> new NotificationListener(
                FakeChannel::quiet, "test", List.of(), LOGGER, WAIT, BACKOFF));
    }

    @Test
    @DisplayName("a channel name that is not an identifier is refused, because it is not a parameter")
    void channelNamesAreCheckedBeforeTheyReachAStatement() {
        // The name goes into `LISTEN <name>` unquoted - it is an identifier, and there is no
        // placeholder for one.
        assertThrows(IllegalArgumentException.class, () -> PostgresNotifications.connector(
                "jdbc:postgresql://localhost/x", "u", "p", 3, "test",
                List.of("nordtal_admin; DROP TABLE discord_user")));
        assertThrows(IllegalArgumentException.class, () -> PostgresNotifications.connector(
                "jdbc:postgresql://localhost/x", "u", "p", 3, "test", List.of()));
    }

    // ---------------------------------------------------------------- driving the loop

    private static void runBriefly(final NotificationListener listener) throws InterruptedException {
        final Thread thread = start(listener);
        Thread.sleep(200);
        stop(listener, thread);
    }

    private static Thread start(final NotificationListener listener) {
        final Thread thread = new Thread(listener::run, "notification-listener-test");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void stop(final NotificationListener listener, final Thread thread)
            throws InterruptedException {
        listener.close();
        thread.join(TimeUnit.SECONDS.toMillis(10));
    }

    // ---------------------------------------------------------------- fakes

    /**
     * A scripted {@link Notifications}: a queue of answers, then either quiet forever or an
     * exception. A script, not a socket.
     */
    private static final class FakeChannel implements Notifications {

        private final Deque<Boolean> script = new ArrayDeque<>();
        private final boolean dieWhenScriptRunsOut;
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
        public synchronized boolean awaitNotification(final Duration timeout) throws SQLException {
            final Boolean next = script.poll();
            if (next != null) {
                return next;
            }
            if (dieWhenScriptRunsOut) {
                throw new SQLException("the connection went away");
            }
            try {
                // Stand in for a quiet interval, so the loop does not spin the CPU while a test
                // watches it.
                Thread.sleep(timeout.toMillis());
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted", interrupted);
            }
            return false;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
