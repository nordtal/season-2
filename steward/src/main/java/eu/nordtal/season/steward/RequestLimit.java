package eu.nordtal.season.steward;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.Deque;

/** Caps requests per key and across all keys in rolling time windows. */
public final class RequestLimit<K> {

    private record Attempt<K>(K key, Instant at) {}

    private final InstantSource clock;
    private final Duration keyWindow;
    private final Duration totalWindow;
    private final int perKey;
    private final int total;
    private final Deque<Attempt<K>> attempts = new ArrayDeque<>();

    public RequestLimit(final InstantSource clock, final Duration window, final int perKey, final int total) {
        this(clock, window, perKey, window, total);
    }

    public RequestLimit(
            final InstantSource clock,
            final Duration keyWindow,
            final int perKey,
            final Duration totalWindow,
            final int total) {
        this.clock = clock;
        this.keyWindow = keyWindow;
        this.totalWindow = totalWindow;
        this.perKey = perKey;
        this.total = total;
    }

    /** Records one attempt if both limits allow it. */
    public synchronized boolean acquire(final K key) {
        final Instant now = clock.instant();
        final Instant keyCutoff = now.minus(keyWindow);
        final Instant totalCutoff = now.minus(totalWindow);
        final Instant cutoff = keyCutoff.isBefore(totalCutoff) ? keyCutoff : totalCutoff;
        while (!attempts.isEmpty() && !attempts.getFirst().at().isAfter(cutoff)) {
            attempts.removeFirst();
        }
        final long recentTotal = attempts.stream()
                .filter(attempt -> attempt.at().isAfter(totalCutoff))
                .count();
        final long recentForKey = attempts.stream()
                .filter(attempt ->
                        attempt.at().isAfter(keyCutoff) && attempt.key().equals(key))
                .count();
        if (recentTotal >= total || recentForKey >= perKey) {
            return false;
        }
        attempts.addLast(new Attempt<>(key, now));
        return true;
    }
}
