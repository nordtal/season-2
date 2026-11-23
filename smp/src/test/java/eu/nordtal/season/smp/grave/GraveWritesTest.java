package eu.nordtal.season.smp.grave;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class GraveWritesTest {

    @Test
    void anOlderPartialCloseFinishingLastLeavesTheNewestRemainder() {
        final FakeGraveDao dao = new FakeGraveDao();
        final UUID grave = dao.add(new byte[] {1, 2, 3});
        final List<Runnable> background = new ArrayList<>();
        final GraveWrites writes = new GraveWrites(dao, background::add, Runnable::run);

        writes.remainder(grave, new byte[] {2, 3});
        writes.remainder(grave, new byte[] {3});
        background.reversed().forEach(Runnable::run);

        assertArrayEquals(new byte[] {3}, dao.contents(grave));
    }

    @Test
    void aStopWritesThePendingRemainderAndLootMarkBeforeTheRestartReads() {
        final FakeGraveDao dao = new FakeGraveDao();
        final UUID partly = dao.add(new byte[] {1, 2, 3});
        final UUID emptied = dao.add(new byte[] {4});
        // The server drops these tasks when the plugin stops, so none of them runs.
        final List<Runnable> dropped = new ArrayList<>();
        final GraveWrites writes = new GraveWrites(dao, dropped::add, dropped::add);
        final AtomicBoolean erased = new AtomicBoolean();

        writes.remainder(partly, new byte[] {3});
        writes.looted(emptied, "looter", () -> erased.set(true));
        writes.flush();

        final List<GraveRow> restored = dao.openGraves();
        assertEquals(List.of(partly), restored.stream().map(GraveRow::id).toList());
        assertArrayEquals(new byte[] {3}, restored.getFirst().contents());
        assertTrue(erased.get(), "the stop erases the emptied grave on the calling thread");
    }

    @Test
    void aWriteTheSchedulerRefusesWaitsForTheFlush() {
        final FakeGraveDao dao = new FakeGraveDao();
        final UUID grave = dao.add(new byte[] {1, 2});
        final GraveWrites writes = new GraveWrites(
                dao,
                task -> {
                    throw new RejectedExecutionException("plugin disabled");
                },
                Runnable::run);

        writes.remainder(grave, new byte[] {2});
        writes.flush();

        assertArrayEquals(new byte[] {2}, dao.contents(grave));
    }

    @Test
    void aStopWaitsForTheWriteInFlight() throws InterruptedException {
        final FakeGraveDao dao = new FakeGraveDao();
        final UUID grave = dao.add(new byte[] {1, 2});
        final CountDownLatch inFlight = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        dao.beforeUpdate(() -> {
            inFlight.countDown();
            awaitQuietly(release);
        });
        final GraveWrites writes =
                new GraveWrites(dao, task -> Thread.ofVirtual().start(task), Runnable::run);
        writes.remainder(grave, new byte[] {2});
        assertTrue(inFlight.await(5, TimeUnit.SECONDS), "the background write starts");

        final Thread stop = Thread.ofVirtual().start(writes::flush);
        stop.join(200);
        assertTrue(stop.isAlive(), "the stop waits for the write in flight");

        release.countDown();
        stop.join(5_000);
        assertFalse(stop.isAlive());
        assertArrayEquals(new byte[] {2}, dao.contents(grave));
    }

    private static void awaitQuietly(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
