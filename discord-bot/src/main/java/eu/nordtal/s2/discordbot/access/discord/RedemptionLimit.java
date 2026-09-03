package eu.nordtal.s2.discordbot.access.discord;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * How many link codes one Discord account may get wrong before the modal stops answering it.
 *
 * <h2>This is the other half of a four-character code</h2>
 * {@code LinkCodes} in {@code :common} generates four characters from a 31-symbol alphabet - 923 521
 * possibilities - and a link code is a bearer credential for taking over somebody else's account
 * link. Nothing else stands between a guesser and that: the modal is a Discord interaction, and
 * Discord's own limits are generous enough to be irrelevant here. Five wrong guesses an hour turns
 * 923 521 possibilities into decades of guessing per account. <b>The length and this class were
 * decided together and neither is safe alone</b> (2026-09-03, todo.md #9).
 *
 * <h2>What counts</h2>
 * Only a code that matched nothing. A correct code obviously does not, and neither does a code that
 * was refused because the account is already linked - that one is a real code held by somebody who
 * clicked the wrong button, and burning their allowance for it would punish the honest case.
 * A successful redemption clears the account's history outright.
 *
 * <h2>In memory, not in the database</h2>
 * The bot is one process and the counters are lost when it restarts. That costs nothing against a
 * space this size - an attacker who could restart the bot would not need to guess codes - and it
 * buys the absence of a table, a sweep and a database round trip inside an interaction that has
 * three seconds to be acknowledged. The map only ever holds accounts that failed within the window;
 * an entry whose failures have all aged out is dropped on the next look, so it cannot grow without
 * bound.
 *
 * <h2>Not thread-safe by accident</h2>
 * Every method is {@code synchronized} on this instance. JDA delivers interactions from several
 * gateway threads and the bot hands them to a worker pool, so two modals really can arrive at once;
 * a lock around a few million operations a season is not worth avoiding.
 */
public final class RedemptionLimit {

    /** The window the cap is measured over. Not configurable - the cap itself is. */
    private static final Duration WINDOW = Duration.ofHours(1);

    private final int maxFailures;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new HashMap<>();

    /**
     * @param maxFailures how many failures are allowed per account per hour; must be positive,
     *                    which {@code Configs} has already checked for the configured value
     * @param clock       the clock to measure the window with - injected so the window can be
     *                    tested without waiting an hour
     */
    public RedemptionLimit(final int maxFailures, final Clock clock) {
        if (maxFailures <= 0) {
            throw new IllegalArgumentException("maxFailures must be positive, got: " + maxFailures);
        }
        this.maxFailures = maxFailures;
        this.clock = clock;
    }

    /**
     * Takes one attempt, if there is one to take.
     *
     * <h2>Why this is one call and not a check followed by a record</h2>
     * It used to be exactly that, and it was racy: the bot hands interactions to a pool of four
     * workers, so four modals from one account could each pass the check before any of them
     * recorded anything, and the account would get eight or nine guesses out of a cap of five.
     * Small against 923 521 possibilities, and still a hole in the one mechanism that makes a
     * four-character code defensible. Admission and accounting are therefore the same synchronized
     * operation, and the caller gives the attempt back when it turns out not to have been a guess.
     *
     * @param discordId the account submitting a code
     * @return how many attempts are left after taking this one - {@code 0} means the next one is
     *         refused - or {@code -1} when there was nothing left to take and the caller must not
     *         look at the code at all
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
     * Gives back the attempt {@link #acquire(String)} took, because it was not a wrong guess after
     * all: the code was right, or it was a real code belonging to an account that is already
     * linked, or the redemption threw before it could answer either way.
     * <p>
     * Only the attempt this caller took is returned - the most recent one - so two workers racing
     * cannot give each other's back. Harmless when there is nothing recorded, which is the normal
     * case after a successful redemption has already cleared the account.
     * </p>
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
     * Forgets an account's failures. Called when a code is actually redeemed: somebody who has just
     * proved they hold a real code is not the case this defends against, and leaving their strikes
     * standing would cap the next link they legitimately make.
     *
     * @param discordId the account
     */
    public synchronized void clear(final String discordId) {
        failures.remove(discordId);
    }

    /**
     * The account's failures inside the window, with everything older dropped. Also drops the map
     * entry entirely when nothing is left, which is what keeps the map the size of "accounts that
     * failed in the last hour" rather than "accounts that ever failed".
     */
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
