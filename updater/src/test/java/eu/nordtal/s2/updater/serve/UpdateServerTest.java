package eu.nordtal.s2.updater.serve;

import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.common.update.UpdateStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loop, without a network, a database or four volumes.
 *
 * <p>Two things here are worth a test and the rest is plumbing. The first is <b>the arithmetic that
 * decides when to wake up</b>: a restart sits in the table for a minute while the proxy counts
 * players down towards it, and a loop that sleeps for its poll interval regardless would fire the
 * restart after the counter had already reached zero. The second is that <b>a drain empties the
 * queue</b> rather than taking one row per wake-up - the case that matters is a request written
 * while the updater was busy with the previous one, whose notification arrived during the run and
 * was never waited for.</p>
 */
class UpdateServerTest {

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final Duration POLL = Duration.ofSeconds(15);

    private final FakeDirectory directory = new FakeDirectory();

    // ---------------------------------------------------------------- when to wake up

    @Test
    void anEmptyInboxWaitsThePollInterval() {
        assertEquals(POLL, server(request -> Outcome.done("x")).waitFor());
    }

    @Test
    void workFurtherAwayThanThePollIntervalStillWaitsThePollInterval() {
        directory.submit(UpdateKind.RESTART, UpdateSource.GAME, "Till", Duration.ofMinutes(10));

        assertEquals(POLL, server(request -> Outcome.done("x")).waitFor());
    }

    @Test
    @DisplayName("a countdown ending sooner than the poll shortens the wait to exactly that")
    void aCountdownEndingSoonerThanThePollShortensTheWait() {
        // This is the bug the method exists to avoid: sixty seconds of countdown, a fifteen-second
        // poll, and a restart that fires up to fifteen seconds after the counter hit zero in front
        // of everybody watching it.
        directory.submit(UpdateKind.RESTART, UpdateSource.GAME, "Till", Duration.ofSeconds(4));

        assertEquals(Duration.ofSeconds(4), server(request -> Outcome.done("x")).waitFor());
    }

    @Test
    void workThatIsAlreadyOverdueStillWaitsASecond() {
        // A row that is due but cannot be claimed - another updater has it for the moment - would
        // otherwise spin this loop as fast as the database can answer.
        directory.at(NOW.minusSeconds(30));
        directory.submit(UpdateKind.APPLY, UpdateSource.DISCORD, "a", Duration.ZERO);
        directory.at(NOW);

        assertEquals(Duration.ofSeconds(1), server(request -> Outcome.done("x")).waitFor());
    }

    // ---------------------------------------------------------------- draining

    @Test
    void everythingDueIsRunInOneDrain() {
        directory.submit(UpdateKind.REPORT, UpdateSource.DISCORD, "a", Duration.ZERO);
        directory.submit(UpdateKind.APPLY, UpdateSource.GAME, "b", Duration.ZERO);

        final List<UpdateKind> ran = new ArrayList<>();
        server(request -> {
            ran.add(request.kind());
            return Outcome.done("done " + request.kind());
        }).drain();

        assertEquals(List.of(UpdateKind.REPORT, UpdateKind.APPLY), ran);
        assertEquals(2, directory.finished().size());
        assertEquals("done REPORT", directory.finished().get(0).result());
    }

    @Test
    void aRequestThatIsNotDueIsLeftAlone() {
        directory.submit(UpdateKind.RESTART, UpdateSource.GAME, "Till", Duration.ofSeconds(60));

        final AtomicInteger ran = new AtomicInteger();
        server(request -> {
            ran.incrementAndGet();
            return Outcome.done("x");
        }).drain();

        assertEquals(0, ran.get());
        assertTrue(directory.pendingRestart().isPresent(), "still counting down");
    }

    @Test
    @DisplayName("the runner's own verdict is what lands in the row")
    void aFailedRunIsWrittenBackAsFailed() {
        final UpdateRequest submitted =
                directory.submit(UpdateKind.APPLY, UpdateSource.DISCORD, "a", Duration.ZERO);

        server(request -> Outcome.failed("the download timed out")).drain();

        final UpdateRequest row = directory.find(submitted.id()).orElseThrow();
        assertEquals(UpdateStatus.FAILED, row.status());
        assertEquals("the download timed out", row.result());
    }

    // ---------------------------------------------------------------- the reconnect loop

    @Test
    @DisplayName("a listener that dies is replaced, and the table is drained on every reconnect")
    void everyReconnectDrainsBeforeItWaitsForAnything() throws Exception {
        // THE rule, the same one the phase listener states: a request written while this process
        // was disconnected produced a notification nobody received, and no later notification will
        // repeat it. So a reconnect that waited first would sit on work that is already there.
        directory.submit(UpdateKind.REPORT, UpdateSource.DISCORD, "a", Duration.ZERO);

        final AtomicInteger connects = new AtomicInteger();
        final AtomicInteger ran = new AtomicInteger();

        final UpdateServer server = new UpdateServer(directory, request -> {
            ran.incrementAndGet();
            return Outcome.done("x");
        }, failingConnector(connects), POLL, fixedClock(), Duration.ofMillis(1));

        final Thread thread = new Thread(server::serve, "test-update-server");
        thread.start();
        try {
            // The connector fails on every await, so the loop reconnects as fast as the backoff
            // allows. Two connects is enough to prove it comes back.
            final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (connects.get() < 2 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(connects.get() >= 2, "the listener was reopened after it failed");
            assertEquals(1, ran.get(), "and the one request waiting was run exactly once");
        } finally {
            server.close();
            thread.join(Duration.ofSeconds(5).toMillis());
        }
    }

    // ---------------------------------------------------------------- helpers

    private UpdateServer server(final RequestRunner runner) {
        return new UpdateServer(directory, runner, never(), POLL, fixedClock(), Duration.ofMillis(1));
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    /** A connector whose connection never reports anything and never dies. */
    private static Notifications.Connector never() {
        return () -> new Notifications() {
            @Override
            public boolean awaitNotification(final Duration timeout) {
                return false;
            }

            @Override
            public void close() {
            }
        };
    }

    /** A connector whose connection dies on the first wait, every time. */
    private static Notifications.Connector failingConnector(final AtomicInteger connects) {
        return () -> {
            connects.incrementAndGet();
            return new Notifications() {
                @Override
                public boolean awaitNotification(final Duration timeout) throws SQLException {
                    throw new SQLException("the socket went away");
                }

                @Override
                public void close() {
                }
            };
        };
    }
}
