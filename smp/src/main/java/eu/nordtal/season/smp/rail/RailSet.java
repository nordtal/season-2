package eu.nordtal.season.smp.rail;

import java.util.AbstractSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** A set of rails kept a chunk at a time, which grows without copying the whole set at once, as {@link RailMap}. */
final class RailSet extends AbstractSet<RailPos> {

    private final Map<Long, Set<RailPos>> chunks = new HashMap<>();
    private int size;

    @Override
    public boolean add(final RailPos pos) {
        if (chunks.computeIfAbsent(RailMap.chunk(pos), _ -> new HashSet<>()).add(pos)) {
            size++;
            return true;
        }
        return false;
    }

    @Override
    public boolean contains(final Object o) {
        if (!(o instanceof RailPos pos)) {
            return false;
        }
        final @Nullable Set<RailPos> chunk = chunks.get(RailMap.chunk(pos));
        return chunk != null && chunk.contains(pos);
    }

    @Override
    public boolean remove(final Object o) {
        if (!(o instanceof RailPos pos)) {
            return false;
        }
        final long key = RailMap.chunk(pos);
        final @Nullable Set<RailPos> chunk = chunks.get(key);
        if (chunk == null || !chunk.remove(pos)) {
            return false;
        }
        size--;
        if (chunk.isEmpty()) {
            chunks.remove(key);
        }
        return true;
    }

    @Override
    public Iterator<RailPos> iterator() {
        return Collections.unmodifiableCollection(
                        chunks.values().stream().flatMap(Set::stream).toList())
                .iterator();
    }

    @Override
    public int size() {
        return size;
    }
}
