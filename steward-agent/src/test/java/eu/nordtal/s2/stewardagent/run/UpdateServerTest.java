package eu.nordtal.s2.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.Actor;
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
    void aWakeUpDrainsLongBeforeTheWaitRunsOut() throws Exception {
        // The hub rings on every signal and every reconnect; the wait itself is only the reconciliation.
        final CountDownLatch ran = new CountDownLatch(1);
        final UpdateServer server = new UpdateServer(
                directory,
                (request, progress) -> {
                    ran.countDown();
                    return Outcome.done("x");
                },
                runner -> false,
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

    /** A run handed to a one-shot stays open: the one-shot settles it, and until then it keeps every other run out. */
    @Test
    void aRunHandedToAOneShotIsLeftOpenForIt() {
        final UpdateRequest submitted = directory.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);

        server(
                        (request, progress) -> {
                            directory.handOver(request.id(), "nordtal-s2-steward-agent-run");
                            return Outcome.handedOver("handed");
                        },
                        "nordtal-s2-steward-agent-run"::equals)
                .drain();

        assertEquals(
                UpdateStatus.RUNNING,
                directory.find(submitted.id()).orElseThrow().status());
        assertTrue(directory.finished().isEmpty(), directory.finished().toString());
    }

    /** Once the one-shot is gone without settling its row, the next wake-up fails the row, so the lock is let go. */
    @Test
    void aRunWhoseOneShotIsGoneIsFailedAtTheNextWakeUp() {
        final UpdateRequest submitted = directory.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);
        final java.util.concurrent.atomic.AtomicBoolean alive = new java.util.concurrent.atomic.AtomicBoolean(true);
        final UpdateServer server = server(
                (request, progress) -> {
                    directory.handOver(request.id(), "nordtal-s2-steward-agent-run");
                    return Outcome.handedOver("handed");
                },
                runner -> alive.get());
        server.drain();
        server.drain();
        assertEquals(
                UpdateStatus.RUNNING,
                directory.find(submitted.id()).orElseThrow().status());

        alive.set(false);
        server.drain();

        assertEquals(
                UpdateStatus.FAILED,
                directory.find(submitted.id()).orElseThrow().status());
    }

    /** The one-shot carries out the run handed to it, and only that one, and settles the row itself. */
    @Test
    void theOneShotCarriesOutTheRunHandedToItAndSettlesIt() {
        final UpdateRequest handed = directory.submit(UpdateKind.UPDATE, Actor.HOST, Duration.ZERO);
        directory.claimNext();
        directory.handOver(handed.id(), "nordtal-s2-steward-agent-run");
        final UpdateServer oneShot = server((request, progress) -> Outcome.done("installed"), runner -> true);

        assertFalse(oneShot.carryOutHanded(handed.id(), "someone-else"), "a run handed to another one-shot");
        assertTrue(oneShot.carryOutHanded(handed.id(), "nordtal-s2-steward-agent-run"));

        final UpdateRequest row = directory.find(handed.id()).orElseThrow();
        assertEquals(UpdateStatus.DONE, row.status());
        assertEquals("installed", row.result());
        assertFalse(oneShot.carryOutHanded(handed.id(), "nordtal-s2-steward-agent-run"), "a settled run");
    }

    private UpdateServer server(final RequestRunner runner) {
        return server(runner, oneShot -> false);
    }

    private UpdateServer server(final RequestRunner runner, final java.util.function.Predicate<String> stillRunning) {
        return new UpdateServer(directory, runner, stillRunning, POLL, fixedClock());
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
