package eu.nordtal.season.smp.rail;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A map from rails, kept a chunk at a time.
 * A plain map of a world's rails copies hundreds of thousands of entries at once when it outgrows its table, which
 * takes more than a tick's share of time; this one grows the table of one chunk at a time.
 */
final class RailMap<V> {

    private final Map<Long, Map<RailPos, V>> chunks = new HashMap<>();
    private int size;

    static long chunk(final RailPos pos) {
        return ((long) (pos.x() >> 4) << 32) | ((pos.z() >> 4) & 0xffffffffL);
    }

    @Nullable
    V get(final RailPos pos) {
        final @Nullable Map<RailPos, V> chunk = chunks.get(chunk(pos));
        return chunk == null ? null : chunk.get(pos);
    }

    boolean containsKey(final RailPos pos) {
        final @Nullable Map<RailPos, V> chunk = chunks.get(chunk(pos));
        return chunk != null && chunk.containsKey(pos);
    }

    /** Puts {@code value} at {@code pos}, and returns the value it replaced. */
    @Nullable
    V put(final RailPos pos, final V value) {
        final @Nullable V old =
                chunks.computeIfAbsent(chunk(pos), _ -> new HashMap<>()).put(pos, value);
        if (old == null) {
            size++;
        }
        return old;
    }

    /** Removes what lies at {@code pos}, and returns it. */
    @Nullable
    V remove(final RailPos pos) {
        final long key = chunk(pos);
        final @Nullable Map<RailPos, V> chunk = chunks.get(key);
        if (chunk == null) {
            return null;
        }
        final @Nullable V old = chunk.remove(pos);
        if (old != null) {
            size--;
            if (chunk.isEmpty()) {
                chunks.remove(key);
            }
        }
        return old;
    }

    int size() {
        return size;
    }
}
