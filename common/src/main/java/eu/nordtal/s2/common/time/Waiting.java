package eu.nordtal.s2.common.time;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** How a process lets time pass: the time now, and a pause a test can skip. */
public interface Waiting {

    /** Returns the time now. */
    Instant now();

    /** Pauses, and returns false when interrupted, with the flag restored, which ends the wait. */
    boolean sleep(Duration duration);

    /** Returns time as it really passes, read from {@code source}. */
    static Waiting on(final InstantSource source) {
        Objects.requireNonNull(source, "source");
        return new Waiting() {
            @Override
            public Instant now() {
                return source.instant();
            }

            @Override
            public boolean sleep(final Duration duration) {
                try {
                    Thread.sleep(duration.toMillis());
                    return true;
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        };
    }

    /**
     * Tries until {@code attempt} answers or {@code patience} has passed, pausing by {@code backoff} between tries.
     * The last try starts no later than the deadline; an interrupt ends the wait at once.
     *
     * @return the first answer, or empty once the patience is spent or the thread was interrupted
     */
    default <T> Optional<T> until(final Supplier<Optional<T>> attempt, final Duration patience, final Backoff backoff) {
        final Instant deadline = now().plus(patience);
        Duration pause = backoff.first();
        while (true) {
            final Optional<T> answer = attempt.get();
            if (answer.isPresent()) {
                return answer;
            }
            final Duration left = Duration.between(now(), deadline);
            if (left.isNegative() || left.isZero() || !sleep(pause.compareTo(left) < 0 ? pause : left)) {
                return Optional.empty();
            }
            pause = backoff.after(pause);
        }
    }
}
