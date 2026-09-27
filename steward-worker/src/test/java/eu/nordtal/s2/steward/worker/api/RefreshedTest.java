package eu.nordtal.s2.steward.worker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Nobody waits for a refresh they did not ask for.
 *
 * A refresh that blocks the reader who triggers it is a trap: whichever request arrives first after the drift
 * cache expires goes to a registry over the internet with the whole response waiting behind it - and behind a
 * {@code synchronized}, so every other request waits too. steward-ui allows ten seconds, so that one request in
 * every sixty times out, and its log says {@code steward-worker could not be reached} about a container that is
 * healthy.
 *
 * The cache was not wrong to be a minute old; it was wrong about who pays for making it new. A reader gets the
 * answer that exists and the refresh happens beside them.
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
    void theFirstAskHasNothingToHandBackSoItWaitsAndOnlyThatOneDoes() {
        final AtomicInteger reads = new AtomicInteger();
        final Later later = new Later();
        final Refreshed<String> value =
                new Refreshed<>(() -> "read " + reads.incrementAndGet(), Duration.ofMinutes(1), later, now::get);

        assertEquals("read 1", value.get());
        assertEquals(1, reads.get());
        assertEquals(0, later.pending(), "nothing to do in the background on the first read");
    }

    @Test
    void aStaleValueIsHandedOverAtOnceAndMadeNewBehindTheReadersBack() {
        final AtomicInteger reads = new AtomicInteger();
        final Later later = new Later();
        final Refreshed<String> value =
                new Refreshed<>(() -> "read " + reads.incrementAndGet(), Duration.ofMinutes(1), later, now::get);

        assertEquals("read 1", value.get());
        tick(Duration.ofMinutes(2));

        // The old answer, immediately: the reader is not the one who pays for the registry round trip.
        assertEquals("read 1", value.get());
        assertEquals(1, reads.get(), "the reader must not have done the read itself");
        assertEquals(1, later.pending());

        later.runAll();
        assertEquals("read 2", value.get());
    }

    @Test
    void tenReadersArrivingAtOnceAskForOneRefreshBetweenThem() {
        final Later later = new Later();
        final Refreshed<String> value = new Refreshed<>(() -> "a value", Duration.ofMinutes(1), later, now::get);

        value.get();
        tick(Duration.ofMinutes(2));
        for (int reader = 0; reader < 10; reader++) {
            value.get();
        }

        assertEquals(1, later.pending(), "one refresh, not ten - the start page has several tabs");
    }

    @Test
    void aRefreshThatFailsKeepsTheOldAnswerAndTheNextReaderAsksAgain() {
        // An unreachable registry is not a reason to have no drift column: the old answer stands, aged, not current.
        final AtomicInteger reads = new AtomicInteger();
        final Later later = new Later();
        final Refreshed<String> value = new Refreshed<>(
                () -> {
                    final int read = reads.incrementAndGet();
                    if (read == 2) {
                        throw new IllegalStateException("the registry is not answering");
                    }
                    return "read " + read;
                },
                Duration.ofMinutes(1),
                later,
                now::get);

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
    void aFirstReadThatFailsIsThrownBecauseThereIsNothingElseToSay() {
        final Refreshed<String> value = new Refreshed<>(
                () -> {
                    throw new IllegalStateException("the registry is not answering");
                },
                Duration.ofMinutes(1),
                new Later(),
                now::get);

        assertThrows(IllegalStateException.class, value::get);
    }

    @Test
    void itSaysHowOldTheAnswerItHandedOverIs() {
        final Later later = new Later();
        final Refreshed<String> value = new Refreshed<>(() -> "a value", Duration.ofMinutes(1), later, now::get);

        value.get();
        assertEquals(now.get(), value.refreshedAt());
        tick(Duration.ofMinutes(2));
        assertTrue(value.refreshedAt().isBefore(now.get()));
    }
}
