package eu.nordtal.s2.proxy.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the standby hands the network back (season-2-ops/121).
 *
 * <p>Both ways of getting this wrong are in these four tests, and they are not symmetric. Too early
 * means everybody is handed back into a proxy that is seconds from stopping - and the live proxy
 * parks once per run, so the second time it would not catch them: the whole network disconnected by
 * the mechanism built to stop exactly that. Too late means a loading screen nobody ever leaves.</p>
 */
class StandbyReturnDecisionTest {

    @Test
    @DisplayName("nobody goes back while the live proxy is still answering and has never stopped")
    void theParkingWindowIsNotAReturn() {
        // The seconds between the park and the stop: both proxies up, the address answering
        // perfectly well. This is the case that makes "is it up" the wrong question.
        assertFalse(StandbyReturn.releases(false, Duration.ZERO));
        assertFalse(StandbyReturn.releases(false, Duration.ofSeconds(30)));
    }

    @Test
    @DisplayName("after an outage, a few seconds of answering is enough")
    void anOutageThenAnAnswerIsTheReturn() {
        assertTrue(StandbyReturn.releases(true, StandbyReturn.SETTLE));
        assertTrue(StandbyReturn.releases(true, Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("one answering tick is not enough, because a bound port is not a started process")
    void oneTickIsNotEnough() {
        assertFalse(StandbyReturn.releases(true, Duration.ZERO));
        assertFalse(StandbyReturn.releases(true, StandbyReturn.SETTLE.minusMillis(1)));
    }

    @Test
    @DisplayName("a swap that never happened releases them anyway, after the rescue window")
    void aCancelledRunStillEnds() {
        // The run was called off between the park and the stop, so the outage never comes. Without
        // this, the standby would hold the whole network for ever waiting for something that was
        // cancelled - and steward-worker, the thing that would normally say so, is in this
        // scenario the process that failed.
        assertFalse(StandbyReturn.releases(false, StandbyReturn.RESCUE_AFTER.minusSeconds(1)));
        assertTrue(StandbyReturn.releases(false, StandbyReturn.RESCUE_AFTER));
    }
}
