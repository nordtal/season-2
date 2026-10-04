package eu.nordtal.s2.steward.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.nordtal.s2.common.time.ManualScheduler;
import eu.nordtal.s2.common.time.Waiting;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** What a pass announces: a change once, nothing for an unchanged answer, and nothing to a feed nobody reads. */
class LiveFeedTest {

    private final AtomicReference<Object> runs = new AtomicReference<>("one run");
    private final AtomicReference<Object> services = new AtomicReference<>("smp running");
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicBoolean failing = new AtomicBoolean();
    private final LiveFeed feed = new LiveFeed(Waiting.on(Clock.systemUTC()), new ManualScheduler());

    LiveFeedTest() {
        feed.watch(Topic.RUNS, () -> {
            reads.incrementAndGet();
            if (failing.get()) {
                throw new IllegalStateException("the database is gone");
            }
            return runs.get();
        });
        feed.watch(Topic.SERVICES, services::get);
    }

    @Test
    void aChangedAnswerIsAnnouncedOnceWithItsNewVersion() {
        final LiveFeed.Subscription reader = feed.subscribe();
        feed.pass(true);
        assertNull(reader.poll(), "the first reading is a baseline, not a change");

        runs.set("two runs");
        feed.pass(true);
        final LiveEvent event = reader.poll();
        assertNotNull(event);
        assertEquals(Topic.RUNS, event.topic());
        assertEquals(LiveFeed.versionOf("two runs"), event.version());

        feed.pass(true);
        assertNull(reader.poll(), "an unchanged answer was announced again");
    }

    @Test
    void aPassNobodyAskedForReadsOnlyTheTimedTopics() {
        final LiveFeed.Subscription reader = feed.subscribe();
        feed.pass(true);
        runs.set("two runs");
        services.set("smp stopped");

        feed.pass(false);

        final LiveEvent event = reader.poll();
        assertNotNull(event);
        assertEquals(Topic.SERVICES, event.topic());
        assertNull(reader.poll(), "a topic only a signal covers was read on the timer");
    }

    @Test
    void nothingIsReadWhileNobodyListensAndTheFirstReaderGetsNoStaleChange() {
        feed.pass(true);
        assertEquals(0, reads.get(), "read for nobody");

        final LiveFeed.Subscription first = feed.subscribe();
        feed.pass(true);
        first.close();
        runs.set("two runs");
        feed.pass(true);

        final LiveFeed.Subscription second = feed.subscribe();
        feed.pass(true);
        assertNull(second.poll(), "a change from before this reader arrived was announced to it");
    }

    @Test
    void aTopicThatCannotBeReadKeepsItsVersion() {
        final LiveFeed.Subscription reader = feed.subscribe();
        feed.pass(true);
        failing.set(true);
        feed.pass(true);
        failing.set(false);
        feed.pass(true);
        assertNull(reader.poll(), "a failed read was announced as a change");
    }
}
