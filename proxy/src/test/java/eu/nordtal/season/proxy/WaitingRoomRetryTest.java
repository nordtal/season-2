package eu.nordtal.season.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.nordtal.season.common.time.ManualScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

/** A player whose re-route failed tries the waiting rooms in turn before the disconnect. */
class WaitingRoomRetryTest {

    private static final NetworkServers SERVERS = new NetworkServers("limbo", "limbo-standby", "hunger-games", "smp");

    private final ManualScheduler scheduler = new ManualScheduler();
    private final List<String> tried = new ArrayList<>();
    private final AtomicInteger gaveUp = new AtomicInteger();

    private WaitingRoomRetry retry(final Set<String> registered) {
        return new WaitingRoomRetry(scheduler, SERVERS, registered::contains, NOPLogger.NOP_LOGGER);
    }

    @Test
    void aFailedMoveLandsInTheOtherWaitingRoomInsteadOfADisconnect() {
        retry(Set.of("limbo", "limbo-standby"))
                .start(
                        "Alex",
                        "limbo",
                        room -> {
                            tried.add(room);
                            return CompletableFuture.completedFuture(true);
                        },
                        gaveUp::incrementAndGet);

        assertEquals(List.of(), tried, "the first try waits for the interval");
        assertEquals(WaitingRoomRetry.INTERVAL, scheduler.pending().getFirst().delay());
        scheduler.runPending();

        assertEquals(List.of("limbo-standby"), tried);
        assertEquals(0, gaveUp.get());
        assertEquals(List.of(), scheduler.pending());
    }

    @Test
    void theRoomsAlternateAndTheDisconnectComesOnlyAfterTheLastTry() {
        retry(Set.of("limbo", "limbo-standby"))
                .start(
                        "Alex",
                        "smp",
                        room -> {
                            tried.add(room);
                            return CompletableFuture.completedFuture(false);
                        },
                        gaveUp::incrementAndGet);

        for (int i = 0; i < WaitingRoomRetry.TRIES; i++) {
            assertEquals(0, gaveUp.get(), "given up after " + i + " tries");
            scheduler.runPending();
        }

        assertEquals(List.of("limbo", "limbo-standby", "limbo", "limbo-standby", "limbo"), tried);
        assertEquals(1, gaveUp.get());
        assertEquals(List.of(), scheduler.pending());
    }

    @Test
    void aFailedFutureCountsAsAFailedTry() {
        retry(Set.of("limbo"))
                .start(
                        "Alex",
                        "limbo",
                        room -> {
                            tried.add(room);
                            return tried.size() == 1
                                    ? CompletableFuture.failedFuture(new IllegalStateException("closed"))
                                    : CompletableFuture.completedFuture(true);
                        },
                        gaveUp::incrementAndGet);

        scheduler.runPending();
        scheduler.runPending();

        assertEquals(List.of("limbo", "limbo"), tried, "without a standby room the same room is tried again");
        assertEquals(0, gaveUp.get());
    }

    @Test
    void withoutAWaitingRoomThePlayerIsDisconnectedAtTheFirstTry() {
        retry(Set.of())
                .start(
                        "Alex",
                        "smp",
                        room -> {
                            tried.add(room);
                            return CompletableFuture.completedFuture(true);
                        },
                        gaveUp::incrementAndGet);

        scheduler.runPending();

        assertEquals(List.of(), tried);
        assertEquals(1, gaveUp.get());
    }

    @Test
    void theNextRoomSkipsOneThatIsNotRegistered() {
        assertEquals(
                "limbo-standby",
                WaitingRoomRetry.nextRoom("limbo", SERVERS, Set.of("limbo", "limbo-standby")::contains));
        assertEquals("limbo", WaitingRoomRetry.nextRoom("limbo", SERVERS, Set.of("limbo")::contains));
        assertEquals("limbo-standby", WaitingRoomRetry.nextRoom("smp", SERVERS, Set.of("limbo-standby")::contains));
        assertNull(WaitingRoomRetry.nextRoom("smp", SERVERS, Set.of("smp")::contains));
    }
}
