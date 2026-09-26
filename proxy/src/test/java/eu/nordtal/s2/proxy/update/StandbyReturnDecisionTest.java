package eu.nordtal.s2.proxy.update;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * When the standby hands the network back.
 *
 * Both ways of getting this wrong are in these four tests, and they are not symmetric. Too early
 * means everybody is handed back into a proxy that is seconds from stopping - and the live proxy
 * parks once per run, so the second time it would not catch them: the whole network disconnected by
 * the mechanism built to stop exactly that. Too late means a loading screen nobody ever leaves.
 */
class StandbyReturnDecisionTest {

    @Test
    void theParkingWindowIsNotAReturn() {
        // Between the park and the stop, both proxies answer fine, which is why "is it up" is the wrong question.
        assertFalse(StandbyReturn.releases(false, Duration.ZERO));
        assertFalse(StandbyReturn.releases(false, Duration.ofSeconds(30)));
    }

    @Test
    void anOutageThenAnAnswerIsTheReturn() {
        assertTrue(StandbyReturn.releases(true, StandbyReturn.SETTLE));
        assertTrue(StandbyReturn.releases(true, Duration.ofMinutes(5)));
    }

    @Test
    void oneTickIsNotEnough() {
        assertFalse(StandbyReturn.releases(true, Duration.ZERO));
        assertFalse(StandbyReturn.releases(true, StandbyReturn.SETTLE.minusMillis(1)));
    }

    @Test
    void aCancelledRunStillEnds() {
        // A run called off between park and stop never brings the outage, and the failed worker cannot say so.
        assertFalse(StandbyReturn.releases(false, StandbyReturn.RESCUE_AFTER.minusSeconds(1)));
        assertTrue(StandbyReturn.releases(false, StandbyReturn.RESCUE_AFTER));
    }
}
