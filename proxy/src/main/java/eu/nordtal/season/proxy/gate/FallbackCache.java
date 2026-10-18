package eu.nordtal.season.proxy.gate;

import eu.nordtal.season.database.access.AccessState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores the access states that the login gate uses when the database does not answer. An entry older than {@code
 * window} is absent.
 */
public final class FallbackCache {

    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    public FallbackCache(final Duration window, final Clock clock) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive, got: " + window);
        }
        this.window = window;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Stores the state if it may join, otherwise deletes the entry of the account. */
    public void remember(final UUID mcUuid, final AccessState state) {
        Objects.requireNonNull(mcUuid, "mcUuid");
        Objects.requireNonNull(state, "state");
        if (state.mayJoin()) {
            entries.put(mcUuid, new Entry(state.locale(), clock.instant()));
        } else {
            entries.remove(mcUuid);
        }
    }

    /** Returns whether the cache holds the account as allowed. */
    public boolean mayJoin(final UUID mcUuid) {
        return current(mcUuid).isPresent();
    }

    /** Returns the cached language of the account, English without an entry. */
    public Locale localeOf(final UUID mcUuid) {
        return current(mcUuid).map(Entry::locale).orElse(Locale.ENGLISH);
    }

    /** Returns the number of entries within the window. */
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
