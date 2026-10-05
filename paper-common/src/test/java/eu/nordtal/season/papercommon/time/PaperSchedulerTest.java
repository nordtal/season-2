package eu.nordtal.season.papercommon.time;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** A duration becomes whole ticks rounded up, so that nothing the server runs comes early. */
class PaperSchedulerTest {

    @Test
    void aWholeTickStaysAndAPartOfOneCountsAsOne() {
        assertEquals(1, PaperScheduler.ticks(PaperScheduler.TICK));
        assertEquals(2, PaperScheduler.ticks(Duration.ofMillis(51)));
        assertEquals(5, PaperScheduler.ticks(Duration.ofMillis(250)));
    }

    @Test
    void nothingAndLessThanNothingIsNoTickAtAll() {
        assertEquals(0, PaperScheduler.ticks(Duration.ZERO));
        assertEquals(0, PaperScheduler.ticks(Duration.ofSeconds(-3)));
    }
}
