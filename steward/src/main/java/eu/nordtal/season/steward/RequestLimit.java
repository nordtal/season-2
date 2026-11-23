package eu.nordtal.season.steward;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/** Caps requests in a rolling time window, both per key and across all keys. */
public final class RequestLimit<K> {

    private record Attempt<K>(K key, Instant at) {}

    private final InstantSource clock;
    private final Duration window;
    private final int perKey;
    private final int total;
    private final Deque<Attempt<K>> attempts = new ArrayDeque<>();

    public RequestLimit(final InstantSource clock, final Duration window, final int perKey, final int total) {
        this.clock = clock;
        this.window = window;
        this.perKey = perKey;
        this.total = total;
    }

    /** Records one attempt if both limits allow it. */
    public boolean acquire(final K key) {
        return acquireOrWait(key).isEmpty();
    }

    /** Records one attempt if both limits allow it, or returns how long {@code key} waits for its next one. */
    public synchronized Optional<Duration> acquireOrWait(final K key) {
        final Instant now = clock.instant();
        final Instant cutoff = now.minus(window);
        while (!attempts.isEmpty() && !attempts.getFirst().at().isAfter(cutoff)) {
            attempts.removeFirst();
        }
        Instant free = now;
        if (attempts.size() >= total) {
            free = attempts.getFirst().at().plus(window);
        }
        final List<Instant> own = attempts.stream()
                .filter(attempt -> attempt.key().equals(key))
                .map(Attempt::at)
                .toList();
        if (own.size() >= perKey) {
            final Instant ownFree = own.get(own.size() - perKey).plus(window);
            free = ownFree.isAfter(free) ? ownFree : free;
        }
        if (free.isAfter(now)) {
            return Optional.of(Duration.between(now, free));
        }
        attempts.addLast(new Attempt<>(key, now));
        return Optional.empty();
    }
}
