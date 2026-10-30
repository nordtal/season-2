package eu.nordtal.season.smp.duel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class NoticeThrottleTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void theFirstNoticeGetsThrough() {
        assertTrue(new NoticeThrottle(3_000).allows(PLAYER, 10_000));
    }

    @Test
    void aSecondNoticeWithinTheIntervalIsHeldBack() {
        final NoticeThrottle throttle = new NoticeThrottle(3_000);
        throttle.allows(PLAYER, 10_000);

        assertFalse(throttle.allows(PLAYER, 12_999));
    }

    @Test
    void aHeldBackNoticeDoesNotRestartTheInterval() {
        final NoticeThrottle throttle = new NoticeThrottle(3_000);
        throttle.allows(PLAYER, 10_000);
        throttle.allows(PLAYER, 12_000);

        assertTrue(throttle.allows(PLAYER, 13_000));
    }

    @Test
    void eachPlayerHasTheirOwnInterval() {
        final NoticeThrottle throttle = new NoticeThrottle(3_000);
        throttle.allows(PLAYER, 10_000);

        assertTrue(throttle.allows(OTHER, 10_001));
    }
}
