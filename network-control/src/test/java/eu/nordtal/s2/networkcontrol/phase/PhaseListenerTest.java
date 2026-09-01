package eu.nordtal.s2.networkcontrol.phase;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.phase.PhaseChange;
import eu.nordtal.s2.common.phase.PhaseDirectory;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reconnect loop in {@link PhaseListener}, against a fake {@link PhaseNotifications}.
 *
 * <h2>Read this before trusting these tests</h2>
 * <b>None of this proves anything about a real dropped socket.</b> A fake that throws when told to
 * is not a network partition, a failed-over database, or a proxy suspended for a minute; and
 * {@code getNotifications} answering {@code null} forever on a silently dead TCP connection - the
 * failure mode the 30-second poll actually exists for - cannot be reproduced in a JVM at all.
 * the row in docs/state-of-play.md#the-unverified-assumptions {@code LISTEN}/{@code NOTIFY} row is closed by a
 * <b>restart drill against a real PostgreSQL with the connection killed underneath the proxy</b>,
 * not by this file.
 * <p>
 * What these tests do pin down is the one rule that is a coding mistake rather than an
 * environmental one: docs/season-phases.md's "every reconnect must re-read the row
 * unconditionally". That is a property of the loop's shape, and the loop's shape is testable.
 * </p>
 */
class PhaseListenerTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(PhaseListenerTest.class);
    private static final Duration WAIT = Duration.ofMillis(20);

    /** Shortened from the production five seconds so several reconnects fit in one test. */
    private static final Duration BACKOFF = Duration.ofMillis(30);

    @Test
    void connectingReReadsTheRowBeforeWaitingForAnyNotification() throws Exception {
        final CountingDirectory directory = new CountingDirectory();
        final PhaseWatch watch = new PhaseWatch(directory, LOGGER, (previous, current) -> { });

        // One channel that never publishes anything at all. If the listener only refreshed on a
        // notification, this would refresh zero times - and a switch made while the proxy was
        // disconnected would be invisible until the next poll.
        final FakeChannel quiet = FakeChannel.quiet();
        final PhaseListener listener = new PhaseListener(() -> quiet, watch, LOGGER, WAIT, BACKOFF);

        runBriefly(listener);

        assertTrue(directory.reads.get() >= 1,
                "notifications are lost while a process is disconnected, so a connect has to re-read "
                        + "whether or not anything arrives afterwards");
    }

    @Test
    void aNotificationTriggersAnotherReadOfTheRow() throws Exception {
        final CountingDirectory directory = new CountingDirectory();
        final PhaseWatch watch = new PhaseWatch(directory, LOGGER, (previous, current) -> { });

        final FakeChannel channel = FakeChannel.publishing(3);
        final PhaseListener listener = new PhaseListener(() -> channel, watch, LOGGER, WAIT, BACKOFF);

        runBriefly(listener);

        assertTrue(directory.reads.get() >= 4,
                "one read for the connect plus one per notification; saw " + directory.reads.get());
    }

    @Test
    void aLostConnectionIsReplacedAndTheNewOneReReadsTheRowAgain() throws Exception {
        final CountingDirectory directory = new CountingDirectory();
        final PhaseWatch watch = new PhaseWatch(directory, LOGGER, (previous, current) -> { });

        // Three connections in a row, each of which dies on its first wait. Every one of them has
        // to re-read on the way in, because a phase switch could have happened in the gap - and
        // nothing will ever tell this process about it if it does not go and look.
        final AtomicInteger opened = new AtomicInteger();
        final CountDownLatch thirdOpened = new CountDownLatch(3);
        final PhaseListener listener = new PhaseListener(() -> {
            opened.incrementAndGet();
            thirdOpened.countDown();
            return FakeChannel.dying();
        }, watch, LOGGER, WAIT, BACKOFF);

        final Thread thread = start(listener);
        assertTrue(thirdOpened.await(30, TimeUnit.SECONDS), "the listener stopped reconnecting");
        stop(listener, thread);

        assertTrue(opened.get() >= 3, "reconnected " + opened.get() + " times");
        assertEquals(opened.get(), directory.reads.get(),
                "exactly one unconditional re-read per connection, no more and no fewer");
    }

    @Test
    void aConnectorThatCannotConnectAtAllKeepsTryingWithoutSpinning() throws Exception {
        final CountingDirectory directory = new CountingDirectory();
        final PhaseWatch watch = new PhaseWatch(directory, LOGGER, (previous, current) -> { });

        // A long backoff for this one, so the assertion below is about the backoff being honoured
        // rather than about how fast the machine running the test happens to be.
        final Duration slowBackoff = Duration.ofSeconds(10);
        final AtomicInteger attempts = new AtomicInteger();
        final CountDownLatch tried = new CountDownLatch(1);
        final PhaseListener listener = new PhaseListener(() -> {
            attempts.incrementAndGet();
            tried.countDown();
            throw new SQLException("the database is not there");
        }, watch, LOGGER, WAIT, slowBackoff);

        final Thread thread = start(listener);
        assertTrue(tried.await(10, TimeUnit.SECONDS));
        Thread.sleep(200);
        stop(listener, thread);

        assertEquals(0, directory.reads.get(), "a connection that never opened has nothing to re-read");
        assertEquals(1, attempts.get(),
                "the backoff is what stops a database outage from becoming a connection-attempt storm");
    }

    @Test
    void closingStopsTheLoopAndClosesTheOpenConnection() throws Exception {
        final CountingDirectory directory = new CountingDirectory();
        final PhaseWatch watch = new PhaseWatch(directory, LOGGER, (previous, current) -> { });

        final FakeChannel channel = FakeChannel.quiet();
        final PhaseListener listener = new PhaseListener(() -> channel, watch, LOGGER, WAIT, BACKOFF);

        final Thread thread = start(listener);
        Thread.sleep(150);
        stop(listener, thread);

        assertFalse(thread.isAlive(),
                "close() has to end the loop, not merely ask it to - the proxy shuts down behind it");
        assertTrue(channel.closed, "the dedicated connection is the proxy's to release on shutdown");
    }

    // ---------------------------------------------------------------- driving the loop

    private static void runBriefly(final PhaseListener listener) throws InterruptedException {
        final Thread thread = start(listener);
        Thread.sleep(200);
        stop(listener, thread);
    }

    private static Thread start(final PhaseListener listener) {
        final Thread thread = new Thread(listener::run, "phase-listener-test");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void stop(final PhaseListener listener, final Thread thread) throws InterruptedException {
        listener.close();
        thread.join(TimeUnit.SECONDS.toMillis(10));
    }

    // ---------------------------------------------------------------- fakes

    /** Counts reads and answers the same phase every time. */
    private static final class CountingDirectory implements PhaseDirectory {

        private final AtomicInteger reads = new AtomicInteger();

        @Override
        public SeasonPhase currentPhase() {
            reads.incrementAndGet();
            return SeasonPhase.PRE_EVENT;
        }

        @Override
        public PhaseChange switchPhase(final SeasonPhase phase, final String actor, final String reason) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * A scripted {@link PhaseNotifications}: a queue of answers, then either quiet forever or an
     * exception. It is a script, not a socket - see this class's own documentation.
     */
    private static final class FakeChannel implements PhaseNotifications {

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
