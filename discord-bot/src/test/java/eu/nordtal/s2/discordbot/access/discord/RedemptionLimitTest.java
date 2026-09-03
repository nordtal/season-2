package eu.nordtal.s2.discordbot.access.discord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cap that makes a four-character link code safe, against a clock that can be moved.
 * <p>
 * Everything here is the arithmetic of a sliding window. What it protects is stated in
 * {@code LinkCodes}: 923 521 possibilities, and this is the only thing between them and somebody
 * with a modal.
 * </p>
 */
class RedemptionLimitTest {

    private static final String SOMEBODY = "111111111111111111";
    private static final String SOMEBODY_ELSE = "222222222222222222";

    /** A clock that stands still until a test moves it. */
    private static final class Movable extends Clock {

        private Instant now = Instant.parse("2026-09-03T12:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        void advance(final Duration by) {
            now = now.plus(by);
        }
    }

    @Test
    void anAccountThatHasNeverGuessedIsAllowed() {
        assertTrue(new RedemptionLimit(5, new Movable()).acquire(SOMEBODY) >= 0);
    }

    @Test
    void theCapIsReachedExactlyOnTheConfiguredNumberOfFailures() {
        final RedemptionLimit limit = new RedemptionLimit(5, new Movable());

        assertEquals(4, limit.acquire(SOMEBODY));
        assertEquals(3, limit.acquire(SOMEBODY));
        assertEquals(2, limit.acquire(SOMEBODY));
        assertEquals(1, limit.acquire(SOMEBODY));
        assertEquals(0, limit.acquire(SOMEBODY), "the fifth is allowed and is the last one");

        assertEquals(-1, limit.acquire(SOMEBODY));
    }

    @Test
    @DisplayName("an attempt that was not a wrong guess is given back")
    void aReleasedAttemptDoesNotCount() {
        // The code was right, or it was a real code on an already-linked account, or the database
        // threw. None of those is evidence of guessing, so none of them may cost an attempt.
        final RedemptionLimit limit = new RedemptionLimit(2, new Movable());

        limit.acquire(SOMEBODY);
        limit.release(SOMEBODY);
        limit.acquire(SOMEBODY);
        limit.release(SOMEBODY);

        assertEquals(1, limit.acquire(SOMEBODY), "two released attempts left the account untouched");
    }

    @Test
    void releasingWithNothingRecordedIsHarmless() {
        // The normal path after a successful redemption: clear() has already emptied the account
        // and the finally still runs.
        final RedemptionLimit limit = new RedemptionLimit(1, new Movable());

        limit.release(SOMEBODY);

        assertEquals(0, limit.acquire(SOMEBODY));
    }

    @Test
    @DisplayName("concurrent modals cannot get more attempts than the cap")
    void admissionIsAtomicUnderConcurrency() throws Exception {
        // The bot hands interactions to a pool of four workers, so this really can happen. A check
        // followed by a separate record - which is what this class did until review - let every
        // racing worker pass the check before any of them had recorded anything.
        final int cap = 5;
        final int threads = 32;
        final RedemptionLimit limit = new RedemptionLimit(cap, new Movable());
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger admitted = new AtomicInteger();

        final ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                    } catch (final InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (limit.acquire(SOMEBODY) >= 0) {
                        admitted.incrementAndGet();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "the pool did not finish");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(cap, admitted.get(),
                "exactly the cap may reach the database, however many workers ask at once");
    }

    @Test
    @DisplayName("the window slides: an hour after the first failure it stops counting")
    void failuresAgeOutOneAtATime() {
        final Movable clock = new Movable();
        final RedemptionLimit limit = new RedemptionLimit(2, clock);

        limit.acquire(SOMEBODY);
        clock.advance(Duration.ofMinutes(30));
        limit.acquire(SOMEBODY);
        assertEquals(-1, limit.acquire(SOMEBODY));

        // Just past an hour after the first one, and only the first one has aged out.
        clock.advance(Duration.ofMinutes(30).plusSeconds(1));

        // The second is still inside the window, so one more attempt closes the door again.
        assertEquals(0, limit.acquire(SOMEBODY));
        assertEquals(-1, limit.acquire(SOMEBODY));
    }

    @Test
    void oneAccountsFailuresDoNotTouchAnother() {
        final RedemptionLimit limit = new RedemptionLimit(1, new Movable());

        limit.acquire(SOMEBODY);

        assertEquals(-1, limit.acquire(SOMEBODY));
        assertEquals(0, limit.acquire(SOMEBODY_ELSE));
    }

    @Test
    @DisplayName("redeeming a real code forgets the strikes")
    void aSuccessfulRedemptionClearsTheAccount() {
        // Somebody who has just proved they hold a real code is not the case this defends against,
        // and their next link must not start capped.
        final RedemptionLimit limit = new RedemptionLimit(2, new Movable());
        limit.acquire(SOMEBODY);
        limit.acquire(SOMEBODY);
        assertEquals(-1, limit.acquire(SOMEBODY));

        limit.clear(SOMEBODY);

        assertEquals(1, limit.acquire(SOMEBODY));
    }

    @Test
    void aCapOfZeroOrLessIsRefusedRatherThanLockingEverybodyOut() {
        assertThrows(IllegalArgumentException.class, () -> new RedemptionLimit(0, new Movable()));
        assertThrows(IllegalArgumentException.class, () -> new RedemptionLimit(-1, new Movable()));
    }
}
