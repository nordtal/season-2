package eu.nordtal.s2.proxy.gate;

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
 * The in-memory last-known state the login gate falls back to while the database is unreachable.
 *
 * Only a state that may join is stored, and an entry older than {@code window} counts as absent.
 */
public final class FallbackCache {

    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    public FallbackCache(final Duration window) {
        this(window, Clock.systemUTC());
    }

    FallbackCache(final Duration window, final Clock clock) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive, got: " + window);
        }
        this.window = window;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Records the outcome of a successful {@code accessState} query, evicting the account unless it may join. */
    public void remember(final UUID mcUuid, final AccessState state) {
        Objects.requireNonNull(mcUuid, "mcUuid");
        Objects.requireNonNull(state, "state");
        if (state.mayJoin()) {
            entries.put(mcUuid, new Entry(state.locale(), clock.instant()));
        } else {
            entries.remove(mcUuid);
        }
    }

    /** Whether this account may be let in from the cache alone, evicting an expired entry. */
    public boolean mayJoin(final UUID mcUuid) {
        return current(mcUuid).isPresent();
    }

    /** The language this account was last seen with, English when it is not cached. */
    public Locale localeOf(final UUID mcUuid) {
        return current(mcUuid).map(Entry::locale).orElse(Locale.ENGLISH);
    }

    /** How many cached accounts are still within the window. */
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

    private record Entry(Locale locale, Instant cachedAt) {}
}
