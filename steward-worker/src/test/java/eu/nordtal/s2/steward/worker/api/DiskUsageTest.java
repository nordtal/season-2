package eu.nordtal.s2.steward.worker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A number for the four services with a volume here, and no field for the rest. */
class DiskUsageTest {

    @TempDir
    Path root;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-23T20:00:00Z"));

    @Test
    @DisplayName("smp has a number; postgres, a service with no volume here, has none rather than zero")
    void onlyTheFourHaveANumber() throws IOException {
        Files.createDirectories(root.resolve("smp"));
        Files.createDirectories(root.resolve("postgres"));
        final DiskUsage usage = new DiskUsage(root, path -> OptionalLong.of(4096), Runnable::run, now::get);

        assertEquals(4096, usage.of("smp").orElseThrow().bytes().getAsLong());
        assertTrue(usage.of("postgres").isEmpty());
        assertTrue(usage.of("limbo").isEmpty(), "no directory, no number");
        assertTrue(new DiskUsage(null, Runnable::run).of("smp").isEmpty(), "no volumes-root, no number");
    }

    @Test
    @DisplayName("measured once per five minutes, and the age travels with the number")
    void cachedForFiveMinutes() throws IOException {
        Files.createDirectories(root.resolve("smp"));
        final AtomicInteger calls = new AtomicInteger();
        final DiskUsage usage =
                new DiskUsage(root, path -> OptionalLong.of(calls.incrementAndGet()), Runnable::run, now::get);

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
    @DisplayName("a du that could not answer is no field, not a zero")
    void aFailedMeasurementIsNoField() throws IOException {
        Files.createDirectories(root.resolve("smp"));
        assertTrue(new DiskUsage(root, path -> OptionalLong.empty(), Runnable::run, now::get)
                .of("smp")
                .isEmpty());
    }

    @Test
    @DisplayName("the real du counts what is on the disk")
    void theRealDu() throws IOException {
        Files.write(root.resolve("world.dat"), new byte[256 * 1024]);
        final long bytes = DiskUsage.du(root).orElseThrow();
        assertTrue(bytes >= 256 * 1024, "du said " + bytes);
    }
}
