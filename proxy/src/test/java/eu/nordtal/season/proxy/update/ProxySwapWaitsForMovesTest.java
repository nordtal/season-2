package eu.nordtal.season.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** A player whose move into a waiting room is under way is transferred to the standby proxy once it has ended. */
class ProxySwapWaitsForMovesTest {

    private final AtomicInteger transferred = new AtomicInteger();

    @Test
    void theTransferWaitsForTheMove() {
        final CompletableFuture<Void> move = new CompletableFuture<>();

        final CompletableFuture<Void> done =
                ProxySwap.afterMove(move, Duration.ofMinutes(1), transferred::incrementAndGet);

        assertEquals(0, transferred.get(), "a transfer now leaves the player on the backend the run stops");
        move.complete(null);
        done.join();
        assertEquals(1, transferred.get());
    }

    @Test
    void aFailedMoveStillEndsInTheTransfer() {
        final CompletableFuture<Void> done = ProxySwap.afterMove(
                CompletableFuture.failedFuture(new IllegalStateException("lost")),
                Duration.ofMinutes(1),
                transferred::incrementAndGet);

        done.join();
        assertEquals(1, transferred.get());
    }

    @Test
    void aMoveThatHangsIsNotWaitedForPastTheLimit() {
        final CompletableFuture<Void> hangs = new CompletableFuture<>();

        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> ProxySwap.afterMove(hangs, Duration.ofMillis(50), transferred::incrementAndGet)
                        .join());
        assertEquals(1, transferred.get());
    }
}
