package eu.nordtal.season.steward.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** A number for the four servers, asked of the agent, and no field for the rest. */
class DiskUsageTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-23T20:00:00Z"));

    @Test
    void smpHasANumberPostgresWhichIsNoServerHasNoneRatherThanZero() {
        final DiskUsage usage = new DiskUsage(
                service -> service.equals("smp") ? OptionalLong.of(4096) : OptionalLong.empty(),
                Runnable::run,
                now::get);

        assertEquals(4096, usage.of("smp").orElseThrow().bytes().getAsLong());
        assertTrue(usage.of("postgres").isEmpty());
        assertTrue(usage.of("limbo").isEmpty(), "the agent has no volume of it, so no number");
    }

    @Test
    void measuredOncePerFiveMinutesAndTheAgeTravelsWithTheNumber() {
        final AtomicInteger calls = new AtomicInteger();
        final DiskUsage usage =
                new DiskUsage(service -> OptionalLong.of(calls.incrementAndGet()), Runnable::run, now::get);

        final Instant first = now.get();
        assertEquals(1, usage.of("smp").orElseThrow().bytes().getAsLong());
        now.updateAndGet(at -> at.plus(Duration.ofMinutes(4)));
        assertEquals(first, usage.of("smp").orElseThrow().at());
        assertEquals(1, calls.get());

        now.updateAndGet(at -> at.plus(Duration.ofMinutes(2)));
        usage.of("smp");
        assertEquals(2, calls.get());
        assertEquals(now.get(), usage.of("smp").orElseThrow().at());
    }

    @Test
    void aDuThatCouldNotAnswerIsNoFieldNotAZero() {
        assertTrue(new DiskUsage(service -> OptionalLong.empty(), Runnable::run, now::get)
                .of("smp")
                .isEmpty());
    }
}
