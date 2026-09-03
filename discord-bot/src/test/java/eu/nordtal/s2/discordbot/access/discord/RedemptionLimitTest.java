package eu.nordtal.s2.discordbot.access.discord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(new RedemptionLimit(5, new Movable()).allows(SOMEBODY));
    }

    @Test
    void theCapIsReachedExactlyOnTheConfiguredNumberOfFailures() {
        final RedemptionLimit limit = new RedemptionLimit(5, new Movable());

        assertEquals(4, limit.recordFailure(SOMEBODY));
        assertEquals(3, limit.recordFailure(SOMEBODY));
        assertEquals(2, limit.recordFailure(SOMEBODY));
        assertEquals(1, limit.recordFailure(SOMEBODY));
        assertTrue(limit.allows(SOMEBODY), "four failures out of five still leaves one");

        assertEquals(0, limit.recordFailure(SOMEBODY));
        assertFalse(limit.allows(SOMEBODY));
    }

    @Test
    @DisplayName("the window slides: an hour after the first failure it stops counting")
    void failuresAgeOutOneAtATime() {
        final Movable clock = new Movable();
        final RedemptionLimit limit = new RedemptionLimit(2, clock);

        limit.recordFailure(SOMEBODY);
        clock.advance(Duration.ofMinutes(30));
        limit.recordFailure(SOMEBODY);
        assertFalse(limit.allows(SOMEBODY));

        // Just past an hour after the first one, and only the first one has aged out.
        clock.advance(Duration.ofMinutes(30).plusSeconds(1));
        assertTrue(limit.allows(SOMEBODY));

        // The second is still inside the window, so one more failure closes the door again.
        assertEquals(0, limit.recordFailure(SOMEBODY));
        assertFalse(limit.allows(SOMEBODY));
    }

    @Test
    void oneAccountsFailuresDoNotTouchAnother() {
        final RedemptionLimit limit = new RedemptionLimit(1, new Movable());

        limit.recordFailure(SOMEBODY);

        assertFalse(limit.allows(SOMEBODY));
        assertTrue(limit.allows(SOMEBODY_ELSE));
    }

    @Test
    @DisplayName("redeeming a real code forgets the strikes")
    void aSuccessfulRedemptionClearsTheAccount() {
        // Somebody who has just proved they hold a real code is not the case this defends against,
        // and their next link must not start capped.
        final RedemptionLimit limit = new RedemptionLimit(2, new Movable());
        limit.recordFailure(SOMEBODY);
        limit.recordFailure(SOMEBODY);
        assertFalse(limit.allows(SOMEBODY));

        limit.clear(SOMEBODY);

        assertTrue(limit.allows(SOMEBODY));
    }

    @Test
    void aCapOfZeroOrLessIsRefusedRatherThanLockingEverybodyOut() {
        assertThrows(IllegalArgumentException.class, () -> new RedemptionLimit(0, new Movable()));
        assertThrows(IllegalArgumentException.class, () -> new RedemptionLimit(-1, new Movable()));
    }
}
