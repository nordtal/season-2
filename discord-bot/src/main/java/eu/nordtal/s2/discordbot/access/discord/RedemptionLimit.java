package eu.nordtal.s2.discordbot.access.discord;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Caps how many wrong link codes one Discord account may try per hour; a four-character code is safe only with it.
 *
 * Only a code that matched nothing counts. Counters live in memory, and every method is synchronized.
 */
public final class RedemptionLimit {

    /** The window the cap is measured over; the cap itself is configurable. */
    private static final Duration WINDOW = Duration.ofHours(1);

    private final int maxFailures;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new HashMap<>();

    /**
     * @param maxFailures how many failures are allowed per account per hour; positive
     * @param clock       the clock to measure the window with
     */
    public RedemptionLimit(final int maxFailures, final Clock clock) {
        if (maxFailures <= 0) {
            throw new IllegalArgumentException("maxFailures must be positive, got: " + maxFailures);
        }
        this.maxFailures = maxFailures;
        this.clock = clock;
    }

    /**
     * Takes one attempt, if there is one to take, in the same lock as the check.
     *
     * @param discordId the account submitting a code
     * @return attempts left after this one, or {@code -1} when none was left and the code must not be looked at
     */
    public synchronized int acquire(final String discordId) {
        final Deque<Instant> recent = recent(discordId);
        if (recent.size() >= maxFailures) {
            return -1;
        }
        recent.addLast(clock.instant());
        failures.put(discordId, recent);
        return maxFailures - recent.size();
    }

    /**
     * Gives back the most recent attempt {@link #acquire(String)} took, because it was not a wrong guess.
     *
     * @param discordId the account
     */
    public synchronized void release(final String discordId) {
        final Deque<Instant> recorded = failures.get(discordId);
        if (recorded == null) {
            return;
        }
        recorded.pollLast();
        if (recorded.isEmpty()) {
            failures.remove(discordId);
        }
    }

    /**
     * Forgets an account's failures, once a code is actually redeemed.
     *
     * @param discordId the account
     */
    public synchronized void clear(final String discordId) {
        failures.remove(discordId);
    }

    /** Returns the account's failures inside the window, dropping older ones and an entry left empty. */
    private Deque<Instant> recent(final String discordId) {
        final Deque<Instant> recorded = failures.get(discordId);
        if (recorded == null) {
            return new ArrayDeque<>();
        }
        final Instant cutoff = clock.instant().minus(WINDOW);
        while (!recorded.isEmpty() && !recorded.peekFirst().isAfter(cutoff)) {
            recorded.removeFirst();
        }
        if (recorded.isEmpty()) {
            failures.remove(discordId);
        }
        return recorded;
    }
}
