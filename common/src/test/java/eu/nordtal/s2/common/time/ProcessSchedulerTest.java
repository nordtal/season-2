package eu.nordtal.s2.common.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProcessSchedulerTest {

    private static final Duration SHORT = Duration.ofMillis(5);

    private final List<RuntimeException> failures = new CopyOnWriteArrayList<>();
    private final ProcessScheduler scheduler = new ProcessScheduler("test", failures::add);

    @AfterEach
    void close() {
        scheduler.close();
    }

    @Test
    void aRepeatingTaskThatThrowsIsReportedAndRunsAgain() throws InterruptedException {
        final CountDownLatch threeRuns = new CountDownLatch(3);
        final var _ = scheduler.every(Duration.ZERO, SHORT, () -> {
            threeRuns.countDown();
            throw new IllegalStateException("every time");
        });

        assertTrue(threeRuns.await(5, TimeUnit.SECONDS), "a throw must not end the repetition");
        assertTrue(failures.size() >= 2, "and every throw is told");
    }

    @Test
    void twoRunsOfOneRepeatingTaskNeverOverlap() throws InterruptedException {
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger most = new AtomicInteger();
        final CountDownLatch fiveRuns = new CountDownLatch(5);
        final var _ = scheduler.every(Duration.ZERO, Duration.ofMillis(1), () -> {
            most.accumulateAndGet(running.incrementAndGet(), Math::max);
            sleep(Duration.ofMillis(10));
            running.decrementAndGet();
            fiveRuns.countDown();
        });

        assertTrue(fiveRuns.await(5, TimeUnit.SECONDS));
        assertEquals(1, most.get(), "a run ten times longer than the period still runs alone");
    }

    @Test
    void aCancelledRepeatingTaskRunsNoMore() throws InterruptedException {
        final AtomicInteger runs = new AtomicInteger();
        final CountDownLatch twoRuns = new CountDownLatch(2);
        final Scheduler.Task task = scheduler.every(Duration.ZERO, SHORT, () -> {
            runs.incrementAndGet();
            twoRuns.countDown();
        });
        assertTrue(twoRuns.await(5, TimeUnit.SECONDS));

        task.cancel();
        sleep(SHORT);
        final int atCancel = runs.get();
        sleep(SHORT.multipliedBy(10));

        assertEquals(atCancel, runs.get());
    }

    @Test
    void aTaskCancelledBeforeItsDelayNeverRuns() {
        final AtomicInteger runs = new AtomicInteger();
        scheduler.after(Duration.ofMillis(50), runs::incrementAndGet).cancel();

        sleep(Duration.ofMillis(100));

        assertEquals(0, runs.get());
    }

    @Test
    void aSerialLaneRunsOneAtATimeInOrderPastAThrow() throws InterruptedException {
        final Executor lane = scheduler.serial();
        final List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger most = new AtomicInteger();
        final CountDownLatch done = new CountDownLatch(1);
        for (int index = 0; index < 20; index++) {
            final int number = index;
            lane.execute(() -> {
                most.accumulateAndGet(running.incrementAndGet(), Math::max);
                sleep(Duration.ofMillis(1));
                order.add(number);
                running.decrementAndGet();
                if (number == 10) {
                    throw new IllegalStateException("the lane goes on");
                }
            });
        }
        lane.execute(done::countDown);

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, most.get());
        assertEquals(java.util.stream.IntStream.range(0, 20).boxed().toList(), order);
        assertEquals(1, failures.size());
    }

    private static void sleep(final Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
