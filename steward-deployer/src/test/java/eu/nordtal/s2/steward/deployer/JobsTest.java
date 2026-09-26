package eu.nordtal.s2.steward.deployer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class JobsTest {

    private final Jobs jobs = new Jobs();

    @Test
    void aListenerThatArrivesLateIsToldEverythingThatAlreadyHappened() throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final Jobs.Job job = jobs.start("deploy", List.of("smp"), output -> {
            output.accept("Pulling smp");
            output.accept("Container nordtal-s2-smp-1  Started");
            done.countDown();
            return 0;
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        waitFor(job);

        final List<String> seen = new CopyOnWriteArrayList<>();
        job.follow(seen::add);

        // Everything, in order, including the closing line, so a reconnect never sees a run appear to begin mid-way.
        assertEquals("Pulling smp", seen.get(0));
        assertEquals("Container nordtal-s2-smp-1  Started", seen.get(1));
        assertTrue(seen.get(2).startsWith("--- DONE"), seen.get(2));
    }

    @Test
    void aJobThatThrowsIsFailedAndSaysWhatThrewRatherThanEndingSilently() throws Exception {
        final Jobs.Job job = jobs.start("deploy", List.of(), output -> {
            throw new IllegalStateException("no image for steward-ui");
        });
        waitFor(job);

        assertEquals(Jobs.State.FAILED, job.state());
        assertTrue(
                job.lines().stream().anyMatch(line -> line.contains("no image for steward-ui")),
                job.lines().toString());
    }

    @Test
    void aListenerThatBlowsUpTakesItselfOutNotTheDeployment() throws Exception {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<String> good = new CopyOnWriteArrayList<>();
        final Jobs.Job job = jobs.start("deploy", List.of(), output -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            output.accept("one");
            output.accept("two");
            return 0;
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        job.follow(line -> {
            throw new RuntimeException("this browser is gone");
        });
        job.follow(good::add);
        release.countDown();
        waitFor(job);

        assertEquals(Jobs.State.DONE, job.state());
        assertTrue(good.contains("one") && good.contains("two"), good.toString());
    }

    @Test
    void aLineWrittenWhileSomebodyIsAttachingReachesThemInsteadOfFallingInTheGap() throws Exception {
        final Jobs.Job job = new Jobs.Job("race", "deploy", List.of("smp"));
        job.append("Pulling smp");

        final List<String> seen = new CopyOnWriteArrayList<>();
        final CountDownLatch replaying = new CountDownLatch(1);
        final Thread deployment = new Thread(() -> {
            await(replaying);
            job.append("Container nordtal-s2-smp-1  Started");
        });
        deployment.start();

        job.follow(line -> {
            seen.add(line);
            // Give the deployment a chance to write its next line before this listener is registered.
            replaying.countDown();
            sleep(150);
        });
        deployment.join(5_000);

        assertEquals(List.of("Pulling smp", "Container nordtal-s2-smp-1  Started"), seen);
    }

    @Test
    void theSixthDeploymentWaitingIsRefusedAndSaysSoRatherThanQueuedForever() throws Exception {
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Jobs.Work blocked = output -> {
            running.countDown();
            release.await(10, TimeUnit.SECONDS);
            return 0;
        };
        jobs.start("deploy", List.of(), blocked);
        assertTrue(running.await(5, TimeUnit.SECONDS), "the first job never started");

        final List<Jobs.Job> waiting = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            waiting.add(jobs.start("deploy", List.of(), blocked));
        }
        final Jobs.Job refused = jobs.start("deploy", List.of(), blocked);

        // Assert before releasing: the moment the first job returns, the queued ones stop waiting.
        assertTrue(
                waiting.stream().allMatch(job -> job.state() == Jobs.State.RUNNING),
                "five waiting deployments is not too many");
        // Not silently dropped and not queued behind the others: a job the caller can read.
        assertEquals(Jobs.State.FAILED, refused.state());
        assertTrue(
                refused.lines().stream().anyMatch(line -> line.contains("refused")),
                refused.lines().toString());
        release.countDown();
    }

    private static void await(final CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitFor(final Jobs.Job job) throws InterruptedException {
        for (int i = 0; i < 100 && job.state() == Jobs.State.RUNNING; i++) {
            Thread.sleep(20);
        }
        assertTrue(job.state() != Jobs.State.RUNNING, "job never finished");
    }
}
