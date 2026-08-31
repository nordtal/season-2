package eu.nordtal.s2.networkcontrol.gate;

import eu.nordtal.s2.common.access.AccessState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The short-lived, in-memory last-known-state cache the login gate falls back to while the
 * database is unreachable. See docs/access-system.md.
 *
 * <h2>The four rules</h2>
 * <ul>
 *   <li><b>Written only on a successful query.</b> {@link #remember(UUID, AccessState)} is the
 *       only writer, and the login gate only ever calls it after {@code accessState(uuid)}
 *       actually returned. This cache never invents an entry.</li>
 *   <li><b>Only "may join right now" is worth remembering.</b> A state whose
 *       {@link AccessState#mayJoin()} is {@code false} is removed rather than stored - it is
 *       never going to let anyone in, so keeping it around only risks serving something stale
 *       later. This also means a successful query that finds access has lapsed since it was last
 *       seen active correctly evicts the earlier positive entry, rather than leaving a stale
 *       "yes" behind for the database to be asked again.</li>
 *   <li><b>Bounded window.</b> An entry older than {@code window} is treated as absent and evicted
 *       on read - see {@link #mayJoin(UUID)}. A long outage closes the door rather than leaving it
 *       open forever.</li>
 *   <li><b>Proxy-process-lived.</b> A plain heap map, no file, no second database. It dies with
 *       the process, same as everything else here.</li>
 * </ul>
 * <p>
 * The caller is responsible for the other rule that is not this class's to enforce: consulting
 * this cache only while the database is actually unreachable. Nothing here checks that, because
 * this class has no way to know it.
 * </p>
 */
public final class FallbackCache {

    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    public FallbackCache(final Duration window) {
        this(window, Clock.systemUTC());
    }

    /** Package-visible so tests can control time without sleeping. */
    FallbackCache(final Duration window, final Clock clock) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive, got: " + window);
        }
        this.window = window;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Records the outcome of a successful {@code accessState} query.
     * <p>
     * Only a state that may join right now is kept; anything else is removed, so a login that
     * finds access has lapsed also clears out an earlier positive entry rather than leaving it to
     * be read later while the database happens to be down.
     * </p>
     *
     * @param mcUuid the account the query was about
     * @param state  the answer the database just gave, must not be {@code null}
     */
    public void remember(final UUID mcUuid, final AccessState state) {
        Objects.requireNonNull(mcUuid, "mcUuid");
        Objects.requireNonNull(state, "state");
        if (state.mayJoin()) {
            entries.put(mcUuid, new Entry(state.locale(), clock.instant()));
        } else {
            entries.remove(mcUuid);
        }
    }

    /**
     * Whether this account may be let in from the cache alone, right now.
     * <p>
     * An entry outside the window is evicted as a side effect of asking, so a cache nobody reads
     * for a while does not silently accumulate outage-window-expired rows forever.
     * </p>
     *
     * @param mcUuid the account attempting to join
     * @return {@code true} only for an account that was seen with active access within the window
     */
    public boolean mayJoin(final UUID mcUuid) {
        return current(mcUuid).isPresent();
    }

    /**
     * @param mcUuid the account attempting to join
     * @return the language it was last seen with, English if it is not in the cache at all - a
     *         "we are having trouble" screen has to render even when the cache has nothing
     */
    public Locale localeOf(final UUID mcUuid) {
        return current(mcUuid).map(Entry::locale).orElse(Locale.ENGLISH);
    }

    /** @return how many accounts of the ones cached are still within the window, mostly for tests and logging */
    public int size() {
        return entries.size();
    }

    private Optional<Entry> current(final UUID mcUuid) {
        final Entry entry = entries.get(mcUuid);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.cachedAt().plus(window).isBefore(clock.instant())) {
            entries.remove(mcUuid, entry);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    private record Entry(Locale locale, Instant cachedAt) {
    }
}
