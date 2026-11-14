package eu.nordtal.season.stewardagent.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.time.ManualScheduler;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** A burst of changes makes one pack build, after the quiet time. */
class DebounceTest {

    private final ManualScheduler scheduler = new ManualScheduler();
    private final AtomicInteger builds = new AtomicInteger();
    private final Debounce debounce =
            new Debounce(scheduler, Duration.ofSeconds(30), scheduler, builds::incrementAndGet);

    @Test
    void threeChangesInARowMakeOneBuild() {
        debounce.request();
        debounce.request();
        debounce.request();

        assertEquals(1, scheduler.pending().size());
        assertEquals(Duration.ofSeconds(30), scheduler.pending().get(0).delay());
        scheduler.runPending();
        assertEquals(1, builds.get());
    }

    @Test
    void nothingIsBuiltBeforeTheQuietTimeIsOver() {
        debounce.request();

        assertEquals(0, builds.get());
    }

    @Test
    void aChangeAfterABuildMakesAnotherOne() {
        debounce.request();
        scheduler.runPending();
        debounce.request();
        scheduler.runPending();

        assertEquals(2, builds.get());
    }

    @Test
    void aBuildAskedForNowRunsAtOnceAndTakesThePlaceOfTheWaitingOne() {
        debounce.request();
        debounce.now();

        assertEquals(1, builds.get());
        assertEquals(0, scheduler.pending().size());
    }
}
