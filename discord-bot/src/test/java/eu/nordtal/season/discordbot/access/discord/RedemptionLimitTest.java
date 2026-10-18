package eu.nordtal.season.discordbot.access.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.DiscordId;
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
import org.junit.jupiter.api.Test;

class RedemptionLimitTest {

    private static final String SOMEBODY = "111111111111111111";
    private static final String SOMEBODY_ELSE = "222222222222222222";

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
        assertTrue(new RedemptionLimit(5, new Movable()).acquire(DiscordId.of(SOMEBODY)) >= 0);
    }

    @Test
    void theCapIsReachedExactlyOnTheConfiguredNumberOfFailures() {
        final RedemptionLimit limit = new RedemptionLimit(5, new Movable());

        assertEquals(4, limit.acquire(DiscordId.of(SOMEBODY)));
        assertEquals(3, limit.acquire(DiscordId.of(SOMEBODY)));
        assertEquals(2, limit.acquire(DiscordId.of(SOMEBODY)));
        assertEquals(1, limit.acquire(DiscordId.of(SOMEBODY)));
        assertEquals(0, limit.acquire(DiscordId.of(SOMEBODY)), "the fifth is allowed and is the last one");

        assertEquals(-1, limit.acquire(DiscordId.of(SOMEBODY)));
    }

    @Test
    void anAttemptThatWasNotAWrongGuessIsGivenBack() {
        // A right code, a linked account and a throwing database do not count as guesses.
        final RedemptionLimit limit = new RedemptionLimit(2, new Movable());

        limit.acquire(DiscordId.of(SOMEBODY));
        limit.release(DiscordId.of(SOMEBODY));
        limit.acquire(DiscordId.of(SOMEBODY));
        limit.release(DiscordId.of(SOMEBODY));

        assertEquals(1, limit.acquire(DiscordId.of(SOMEBODY)), "two released attempts left the account untouched");
    }

    @Test
    void releasingWithNothingRecordedIsHarmless() {
        // clear() has emptied the account after a successful redemption, so the finally block releases nothing.
        final RedemptionLimit limit = new RedemptionLimit(1, new Movable());

        limit.release(DiscordId.of(SOMEBODY));

        assertEquals(0, limit.acquire(DiscordId.of(SOMEBODY)));
    }

    @Test
    void concurrentModalsCannotGetMoreAttemptsThanTheCap() throws Exception {
        // Interactions run on four workers, so a check followed by a record can race.
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
                    if (limit.acquire(DiscordId.of(SOMEBODY)) >= 0) {
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

        assertEquals(cap, admitted.get(), "exactly the cap may reach the database, however many workers ask at once");
    }

    @Test
    void theWindowSlidesAnHourAfterTheFirstFailureItStopsCounting() {
        final Movable clock = new Movable();
        final RedemptionLimit limit = new RedemptionLimit(2, clock);

        limit.acquire(DiscordId.of(SOMEBODY));
        clock.advance(Duration.ofMinutes(30));
        limit.acquire(DiscordId.of(SOMEBODY));
        assertEquals(-1, limit.acquire(DiscordId.of(SOMEBODY)));

        clock.advance(Duration.ofMinutes(30).plusSeconds(1));

        assertEquals(0, limit.acquire(DiscordId.of(SOMEBODY)));
        assertEquals(-1, limit.acquire(DiscordId.of(SOMEBODY)));
    }

    @Test
    void oneAccountsFailuresDoNotTouchAnother() {
        final RedemptionLimit limit = new RedemptionLimit(1, new Movable());

        limit.acquire(DiscordId.of(SOMEBODY));

        assertEquals(-1, limit.acquire(DiscordId.of(SOMEBODY)));
        assertEquals(0, limit.acquire(DiscordId.of(SOMEBODY_ELSE)));
    }

    @Test
    void redeemingARealCodeForgetsTheStrikes() {
        final RedemptionLimit limit = new RedemptionLimit(2, new Movable());
        limit.acquire(DiscordId.of(SOMEBODY));
        limit.acquire(DiscordId.of(SOMEBODY));
        assertEquals(-1, limit.acquire(DiscordId.of(SOMEBODY)));

        limit.clear(DiscordId.of(SOMEBODY));

        assertEquals(1, limit.acquire(DiscordId.of(SOMEBODY)));
    }

    @Test
    void aCapOfZeroOrLessIsRefusedRatherThanLockingEverybodyOut() {
        assertThrows(IllegalArgumentException.class, () -> new RedemptionLimit(0, new Movable()));
        assertThrows(IllegalArgumentException.class, () -> new RedemptionLimit(-1, new Movable()));
    }
}
