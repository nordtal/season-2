package eu.nordtal.season.steward;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.Deque;

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
    public synchronized boolean acquire(final K key) {
        final Instant now = clock.instant();
        final Instant cutoff = now.minus(window);
        while (!attempts.isEmpty() && !attempts.getFirst().at().isAfter(cutoff)) {
            attempts.removeFirst();
        }
        if (attempts.size() >= total
                || attempts.stream()
                                .filter(attempt -> attempt.key().equals(key))
                                .count()
                        >= perKey) {
            return false;
        }
        attempts.addLast(new Attempt<>(key, now));
        return true;
    }
}
