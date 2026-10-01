package eu.nordtal.s2.steward.serve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.update.UpdateKind;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.database.update.UpdateStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The loop, without a network, a database or four volumes.
 *
 * It checks when the loop wakes for a due restart, and that a drain empties the queue rather than taking one row.
 */
class UpdateServerTest {

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final Duration POLL = Duration.ofSeconds(15);

    private final FakeDirectory directory = new FakeDirectory();

    @Test
    void anEmptyInboxWaitsThePollInterval() {
        assertEquals(POLL, server((request, progress) -> Outcome.done("x")).waitFor());
    }

    @Test
    void workFurtherAwayThanThePollIntervalStillWaitsThePollInterval() {
        directory.submit(UpdateKind.RESTART, Actor.HOST, Duration.ofMinutes(10));

        assertEquals(POLL, server((request, progress) -> Outcome.done("x")).waitFor());
    }

    @Test
    void aCountdownEndingSoonerThanThePollShortensTheWaitToExactlyThat() {
        // The bug this exists to avoid: a slow poll firing a restart seconds after the counter hit zero on camera.
        directory.submit(UpdateKind.RESTART, Actor.HOST, Duration.ofSeconds(4));

        assertEquals(
                Duration.ofSeconds(4),
                server((request, progress) -> Outcome.done("x")).waitFor());
    }

    @Test
    void workThatIsAlreadyOverdueStillWaitsASecond() {
        // A row that is due but cannot be claimed would otherwise spin this loop as fast as the database can answer.
        directory.at(NOW.minusSeconds(30));
        directory.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);
        directory.at(NOW);

        assertEquals(
                Duration.ofSeconds(1),
                server((request, progress) -> Outcome.done("x")).waitFor());
    }

    @Test
    void everythingDueIsRunInOneDrain() {
        directory.submit(UpdateKind.BACKUP, Actor.HOST, Duration.ZERO);
        directory.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);

        final List<UpdateKind> ran = new ArrayList<>();
        server((request, progress) -> {
                    ran.add(request.kind());
                    return Outcome.done("done " + request.kind());
                })
                .drain();

        assertEquals(List.of(UpdateKind.BACKUP, UpdateKind.UPDATE), ran);
        assertEquals(2, directory.finished().size());
        assertEquals("done BACKUP", directory.finished().get(0).result());
    }

    @Test
    void aRequestThatIsNotDueIsLeftAlone() {
        directory.submit(UpdateKind.RESTART, Actor.HOST, Duration.ofSeconds(60));

        final AtomicInteger ran = new AtomicInteger();
        server((request, progress) -> {
                    ran.incrementAndGet();
                    return Outcome.done("x");
                })
                .drain();

        assertEquals(0, ran.get());
        assertTrue(directory.countingDown().isPresent(), "still counting down");
    }

    @Test
    void theRunnersOwnVerdictIsWhatLandsInTheRow() {
        final UpdateRequest submitted = directory.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);

        server((request, progress) -> Outcome.failed("the download timed out")).drain();

        final UpdateRequest row = directory.find(submitted.id()).orElseThrow();
        assertEquals(UpdateStatus.FAILED, row.status());
        assertEquals("the download timed out", row.result());
    }

    @Test
    void aHandedOverRunGoesBackToTheInboxAndThisWorkerTakesNothingElse() {
        // Steward is about to exit and must not start a second request.
        final UpdateRequest update = directory.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);
        directory.submit(UpdateKind.BACKUP, Actor.HOST, Duration.ZERO);

        final List<UpdateKind> ran = new ArrayList<>();
        final UpdateServer server = server((request, progress) -> {
            ran.add(request.kind());
            // Once, or a loop claiming the handed-over row again never returns from drain().
            return ran.size() == 1 ? Outcome.handedOver("{\"stage\":\"RESOLVING\"}") : Outcome.done("again");
        });
        server.drain();

        assertEquals(List.of(UpdateKind.UPDATE), ran);
        final UpdateRequest row = directory.find(update.id()).orElseThrow();
        assertEquals(UpdateStatus.PENDING, row.status());
        assertEquals("{\"stage\":\"RESOLVING\"}", row.result());
        assertTrue(directory.finished().isEmpty(), "a handover is not a finish");
    }

    @Test
    void serveReturnsOnceItHasHandedARunOver() throws Exception {
        // Returning ends the process, and Docker restarts the container on the new jar.
        directory.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);
        final UpdateServer server = server((request, progress) -> Outcome.handedOver("{}"));

        final Thread thread = new Thread(server::serve, "test-update-server");
        // A daemon, so a hang fails the test instead of keeping the JVM alive.
        thread.setDaemon(true);
        thread.start();
        thread.join(Duration.ofSeconds(5).toMillis());

        final boolean stillServing = thread.isAlive();
        server.close();
        assertEquals(false, stillServing, "serve() is still waiting for requests after handing one over");
    }

    @Test
    void aWakeUpDrainsLongBeforeTheWaitRunsOut() throws Exception {
        // The hub rings on every signal and every reconnect; the wait itself is only the reconciliation.
        final CountDownLatch ran = new CountDownLatch(1);
        final UpdateServer server = new UpdateServer(
                directory,
                (request, progress) -> {
                    ran.countDown();
                    return Outcome.done("x");
                },
                Duration.ofMinutes(10),
                fixedClock());

        final Thread thread = new Thread(server::serve, "test-update-server");
        thread.setDaemon(true);
        thread.start();
        try {
            // Parked on the doorbell first, so the row can only be found through the wake-up.
            final long parked = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (thread.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < parked) {
                Thread.onSpinWait();
            }
            directory.submit(UpdateKind.BACKUP, Actor.HOST, Duration.ZERO);
            server.wake();
            assertTrue(ran.await(5, TimeUnit.SECONDS), "the request waited for the reconciliation, not the wake-up");
        } finally {
            server.close();
            thread.join(Duration.ofSeconds(5).toMillis());
        }
        assertFalse(thread.isAlive(), "close() has to end the wait, not only the next drain");
    }

    private UpdateServer server(final RequestRunner runner) {
        return new UpdateServer(directory, runner, POLL, fixedClock());
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
