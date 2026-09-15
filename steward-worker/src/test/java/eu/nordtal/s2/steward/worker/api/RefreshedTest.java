package eu.nordtal.s2.steward.worker.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nobody waits for a refresh they did not ask for.
 *
 * <p><b>Measured on the dev host, 2026-09-14.</b> {@code GET /api/services} took <b>11.5 s</b>
 * whenever the drift cache had just expired and <b>1.95 s</b> otherwise, because whichever request
 * arrived first after the minute was up went to a registry over the internet with the whole
 * response waiting behind it - and behind a {@code synchronized}, so every other request waited
 * too. steward-ui allows ten seconds, so that one request in every sixty timed out, and its log
 * said {@code steward-worker could not be reached} about a container that was healthy. Once a
 * minute. For hours.</p>
 *
 * <p>The cache was not wrong to be a minute old; it was wrong about <em>who pays</em> for making
 * it new. A reader gets the answer that exists and the refresh happens beside them.</p>
 */
class RefreshedTest {

    /** An executor that runs nothing until a test says so, which is what makes "beside" visible. */
    private static final class Later implements java.util.concurrent.Executor {
        private final Deque<Runnable> queued = new ArrayDeque<>();

        @Override
        public void execute(final Runnable command) {
            queued.add(command);
        }

        int pending() {
            return queued.size();
        }

        void runAll() {
            while (!queued.isEmpty()) {
                queued.poll().run();
            }
        }
    }

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-14T18:00:00Z"));

    private void tick(final Duration by) {
        now.updateAndGet(at -> at.plus(by));
    }

    @Test
    @DisplayName("the first ask has nothing to hand back, so it waits - and only that one does")
    void theFirstOneBlocks() {
        final AtomicInteger reads = new AtomicInteger();
        final Later later = new Later();
        final Refreshed<String> value = new Refreshed<>(
                () -> "read " + reads.incrementAndGet(), Duration.ofMinutes(1), later, now::get);

        assertEquals("read 1", value.get());
        assertEquals(1, reads.get());
        assertEquals(0, later.pending(), "nothing to do in the background on the first read");
    }

    @Test
    @DisplayName("a stale value is handed over at once and made new behind the reader's back")
    void staleIsServedAndRefreshed() {
        final AtomicInteger reads = new AtomicInteger();
        final Later later = new Later();
        final Refreshed<String> value = new Refreshed<>(
                () -> "read " + reads.incrementAndGet(), Duration.ofMinutes(1), later, now::get);

        assertEquals("read 1", value.get());
        tick(Duration.ofMinutes(2));

        // The old answer, immediately. This is the whole point: the reader is not the one who pays
        // for the registry round trip.
        assertEquals("read 1", value.get());
        assertEquals(1, reads.get(), "the reader must not have done the read itself");
        assertEquals(1, later.pending());

        later.runAll();
        assertEquals("read 2", value.get());
    }

    @Test
    @DisplayName("ten readers arriving at once ask for one refresh between them")
    void onlyOneRefreshIsInFlight() {
        final Later later = new Later();
        final Refreshed<String> value = new Refreshed<>(
                () -> "a value", Duration.ofMinutes(1), later, now::get);

        value.get();
        tick(Duration.ofMinutes(2));
        for (int reader = 0; reader < 10; reader++) {
            value.get();
        }

        assertEquals(1, later.pending(), "one refresh, not ten - the start page has several tabs");
    }

    @Test
    @DisplayName("a refresh that fails keeps the old answer, and the next reader asks again")
    void aFailedRefreshIsNotPoison() {
        // The registry being unreachable is not a reason to have no drift column. The answer goes
        // on saying what it said, and it carries its own age - which is what makes a stale one
        // readable as stale rather than as current.
        final AtomicInteger reads = new AtomicInteger();
        final Later later = new Later();
        final Refreshed<String> value = new Refreshed<>(() -> {
            final int read = reads.incrementAndGet();
            if (read == 2) {
                throw new IllegalStateException("the registry is not answering");
            }
            return "read " + read;
        }, Duration.ofMinutes(1), later, now::get);

        assertEquals("read 1", value.get());
        tick(Duration.ofMinutes(2));
        value.get();
        later.runAll();

        assertEquals("read 1", value.get(), "the old answer survives a failed refresh");
        assertEquals(1, later.pending(), "and the next reader may try again");
        later.runAll();
        assertEquals("read 3", value.get());
    }

    @Test
    @DisplayName("a first read that fails is thrown, because there is nothing else to say")
    void theFirstFailureIsAnError() {
        final Refreshed<String> value = new Refreshed<>(() -> {
            throw new IllegalStateException("the registry is not answering");
        }, Duration.ofMinutes(1), new Later(), now::get);

        assertThrows(IllegalStateException.class, value::get);
    }

    @Test
    @DisplayName("it says how old the answer it handed over is")
    void theAgeIsReadable() {
        final Later later = new Later();
        final Refreshed<String> value = new Refreshed<>(
                () -> "a value", Duration.ofMinutes(1), later, now::get);

        value.get();
        assertEquals(now.get(), value.refreshedAt());
        tick(Duration.ofMinutes(2));
        assertTrue(value.refreshedAt().isBefore(now.get()));
    }
}
