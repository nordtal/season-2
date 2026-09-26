package eu.nordtal.jcore.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Finding 8: the old loader had no locking at all. A synthetic probe with 8 threads on one file
 * produced 116 read errors. Runtime reload makes that a live concern rather than a theoretical
 * one.
 */
class ConfigConcurrencyTest {

    private static final int THREADS = 8;
    private static final int ITERATIONS = 40;

    @TempDir
    Path directory;

    @Test
    @DisplayName("finding 8: concurrent reloads on one file produce no read errors")
    void concurrentReloadsAreSafe() throws Exception {
        final Path file = directory.resolve("payments.yml");
        final ConfigHandle<TestSpecs.Payments> handle = ConfigLoader.builder(file, TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();

        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(THREADS);
        final AtomicInteger reads = new AtomicInteger();

        for (int t = 0; t < THREADS; t++) {
            final int index = t;
            Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < ITERATIONS; i++) {
                        if (index % 2 == 0) {
                            handle.reload();
                        } else {
                            // Reading through the handle must never observe a half-applied
                            // reload, and must never throw.
                            assertEquals(10L, handle.get().checkIntervalSeconds());
                            assertEquals("%s EUR", handle.get().balance().format());
                            reads.incrementAndGet();
                        }
                    }
                } catch (Throwable e) {
                    failures.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "the threads did not finish");

        assertAll(
                () -> assertEquals(List.of(), failures, "concurrent access produced errors"),
                () -> assertTrue(reads.get() > 0, "the reader threads did no work"),
                () -> assertTrue(Files.isRegularFile(file), "the file survived"));
    }

    @Test
    @DisplayName("two independent handles on the same file serialise against each other")
    void twoHandlesOnOneFileSerialise() throws Exception {
        final Path file = directory.resolve("shared.yml");
        final ConfigHandle<TestSpecs.Payments> first = ConfigLoader.builder(file, TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();
        final ConfigHandle<TestSpecs.Payments> second = ConfigLoader.builder(file, TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();

        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(2);

        for (ConfigHandle<TestSpecs.Payments> handle : List.of(first, second)) {
            Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < ITERATIONS; i++) {
                        handle.save();
                        handle.reload();
                    }
                } catch (Throwable e) {
                    failures.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "the threads did not finish");
        assertEquals(List.of(), failures, "two handles on one file collided");
    }
}
