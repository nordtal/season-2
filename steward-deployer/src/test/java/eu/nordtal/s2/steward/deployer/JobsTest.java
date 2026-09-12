package eu.nordtal.s2.steward.deployer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobsTest {

    private final Jobs jobs = new Jobs();

    @Test
    @DisplayName("a listener that arrives late is told everything that already happened")
    void replaysWhatItMissed() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Jobs.Job job = jobs.start("deploy", List.of("smp"), output -> {
            output.accept("Pulling smp");
            output.accept("Container nordtal-s2-smp-1  Started");
            done.countDown();
            return 0;
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        waitFor(job);

        List<String> seen = new CopyOnWriteArrayList<>();
        job.follow(seen::add);

        // Everything, in order, including the closing line - a browser that reconnects mid-deploy
        // must not see a run that appears to begin in the middle.
        assertEquals("Pulling smp", seen.get(0));
        assertEquals("Container nordtal-s2-smp-1  Started", seen.get(1));
        assertTrue(seen.get(2).startsWith("--- DONE"), seen.get(2));
    }

    @Test
    @DisplayName("a job that throws is FAILED and says what threw, rather than ending silently")
    void carriesTheFailureOut() throws Exception {
        Jobs.Job job = jobs.start("deploy", List.of(), output -> {
            throw new IllegalStateException("no image for steward-ui");
        });
        waitFor(job);

        assertEquals(Jobs.State.FAILED, job.state());
        assertTrue(job.lines().stream().anyMatch(line -> line.contains("no image for steward-ui")),
                job.lines().toString());
    }

    @Test
    @DisplayName("a listener that blows up takes itself out, not the deployment")
    void oneBadListenerDoesNotStopTheRun() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> good = new CopyOnWriteArrayList<>();
        Jobs.Job job = jobs.start("deploy", List.of(), output -> {
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

    private static void waitFor(Jobs.Job job) throws InterruptedException {
        for (int i = 0; i < 100 && job.state() == Jobs.State.RUNNING; i++) {
            Thread.sleep(20);
        }
        assertTrue(job.state() != Jobs.State.RUNNING, "job never finished");
    }
}
