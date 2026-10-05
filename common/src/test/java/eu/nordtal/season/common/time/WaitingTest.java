package eu.nordtal.season.common.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WaitingTest {

    /** Time that moves only when something pauses, recording every pause. */
    private static final class Driven implements Waiting {
        private Instant now = Instant.parse("2026-09-30T00:00:00Z");
        private final List<Duration> pauses = new ArrayList<>();

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public boolean sleep(final Duration duration) {
            pauses.add(duration);
            now = now.plus(duration);
            return true;
        }
    }

    @Test
    void theFirstAnswerEndsTheWait() {
        final Driven time = new Driven();
        final int[] tries = {0};

        final Optional<String> answer = time.until(
                () -> ++tries[0] == 3 ? Optional.of("up") : Optional.empty(),
                Duration.ofMinutes(1),
                new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(10)));

        assertEquals(Optional.of("up"), answer);
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), time.pauses);
    }

    @Test
    void theBackoffDoublesUpToItsLongestPause() {
        final Backoff backoff = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(3));
        assertEquals(Duration.ofSeconds(2), backoff.after(Duration.ofSeconds(1)));
        assertEquals(Duration.ofSeconds(3), backoff.after(Duration.ofSeconds(2)));
        assertEquals(Duration.ofSeconds(5), Backoff.fixed(Duration.ofSeconds(5)).after(Duration.ofSeconds(5)));
    }

    @Test
    void thePatienceBoundsTheWaitAndTheLastPauseStopsAtTheDeadline() {
        final Driven time = new Driven();
        final Instant start = time.now();

        final Optional<String> answer =
                time.until(Optional::empty, Duration.ofSeconds(10), Backoff.fixed(Duration.ofSeconds(4)));

        assertTrue(answer.isEmpty());
        assertEquals(List.of(Duration.ofSeconds(4), Duration.ofSeconds(4), Duration.ofSeconds(2)), time.pauses);
        assertEquals(start.plusSeconds(10), time.now());
    }

    @Test
    void anInterruptEndsTheWaitAtOnce() {
        final Waiting interrupted = new Waiting() {
            @Override
            public Instant now() {
                return Instant.EPOCH;
            }

            @Override
            public boolean sleep(final Duration duration) {
                return false;
            }
        };

        assertTrue(interrupted
                .until(Optional::empty, Duration.ofHours(1), Backoff.fixed(Duration.ofSeconds(1)))
                .isEmpty());
    }
}
