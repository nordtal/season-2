package eu.nordtal.season.steward.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.time.Waiting;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Tests that a short hiccup on the way to the pack is no dead pack, and a lasting failure is one. */
class PackProbeTest {

    private static final String PACK = "https://pack.nordtal.eu/b0ab45113f5e87e6b7dec35eb3d772d131ce038d.zip";

    /** Time that moves only by the pauses, which it records. */
    private static final class Pauses implements Waiting {
        private Instant now = Instant.parse("2026-11-15T19:02:00Z");
        final List<Duration> taken = new ArrayList<>();

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public boolean sleep(final Duration duration) {
            taken.add(duration);
            now = now.plus(duration);
            return true;
        }
    }

    @Test
    void aPackThatAnswersAtOnceIsAliveWithoutAPause() {
        final Pauses pauses = new Pauses();

        assertEquals(Optional.empty(), new PackProbe(uri -> true, pauses).dead(PACK));
        assertTrue(pauses.taken.isEmpty());
    }

    @Test
    void aPackThatFailsTwiceAndThenAnswersIsAlive() {
        final Pauses pauses = new Pauses();
        final AtomicInteger tries = new AtomicInteger();

        assertEquals(Optional.empty(), new PackProbe(uri -> tries.incrementAndGet() > 2, pauses).dead(PACK));
        assertEquals(3, tries.get());
        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(10)), pauses.taken);
    }

    @Test
    void aPackThatFailsForAMinuteIsDead() {
        final Pauses pauses = new Pauses();
        final AtomicInteger tries = new AtomicInteger();

        assertEquals(
                Optional.of(PACK),
                new PackProbe(
                                uri -> {
                                    tries.incrementAndGet();
                                    return false;
                                },
                                pauses)
                        .dead(PACK));
        assertEquals(PackProbe.PATIENCE, pauses.taken.stream().reduce(Duration.ZERO, Duration::plus));
        assertEquals(6, tries.get(), "after 0, 5, 15, 35, 55 and 60 seconds");
    }

    @Test
    void anAddressThatIsNoUrlIsDeadWithoutATry() {
        final Pauses pauses = new Pauses();

        assertEquals(Optional.of("not a url"), new PackProbe(uri -> true, pauses).dead("not a url"));
        assertTrue(pauses.taken.isEmpty());
    }

    @Test
    void anAddressWhoseSchemeNoRequestTakesIsDead() {
        final String ftp = "ftp://example.org/pack.zip";
        final PackProbe probe = PackProbe.web(new Pauses());

        assertEquals(Optional.of(ftp), probe.dead(ftp));
    }
}
