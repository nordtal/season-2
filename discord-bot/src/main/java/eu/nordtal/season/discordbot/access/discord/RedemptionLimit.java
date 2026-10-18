package eu.nordtal.season.discordbot.access.discord;

import eu.nordtal.season.common.id.DiscordId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Caps how many wrong link codes one Discord account may try per hour.
 * A four-character code is safe only with the cap. Only a code that matched nothing counts.
 * The counters are in memory, and every method is synchronized.
 */
public final class RedemptionLimit {

    private static final Duration WINDOW = Duration.ofHours(1);

    private final int maxFailures;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new HashMap<>();

    /** @param maxFailures the failures allowed per account and hour, positive */
    public RedemptionLimit(final int maxFailures, final Clock clock) {
        if (maxFailures <= 0) {
            throw new IllegalArgumentException("maxFailures must be positive, got: " + maxFailures);
        }
        this.maxFailures = maxFailures;
        this.clock = clock;
    }

    /**
     * Takes one attempt, if one is left, in the same lock as the check.
     *
     * @return the attempts left after this one, or {@code -1} when none was left and the code must not be looked at
     */
    public synchronized int acquire(final DiscordId discordId) {
        final Deque<Instant> recent = recent(discordId);
        if (recent.size() >= maxFailures) {
            return -1;
        }
        recent.addLast(clock.instant());
        failures.put(discordId.value(), recent);
        return maxFailures - recent.size();
    }

    public synchronized void release(final DiscordId discordId) {
        final Deque<Instant> recorded = failures.get(discordId.value());
        if (recorded == null) {
            return;
        }
        recorded.pollLast();
        if (recorded.isEmpty()) {
            failures.remove(discordId.value());
        }
    }

    public synchronized void clear(final DiscordId discordId) {
        failures.remove(discordId.value());
    }

    private Deque<Instant> recent(final DiscordId discordId) {
        final Deque<Instant> recorded = failures.get(discordId.value());
        if (recorded == null) {
            return new ArrayDeque<>();
        }
        final Instant cutoff = clock.instant().minus(WINDOW);
        while (!recorded.isEmpty() && !recorded.peekFirst().isAfter(cutoff)) {
            recorded.removeFirst();
        }
        if (recorded.isEmpty()) {
            failures.remove(discordId.value());
        }
        return recorded;
    }
}
