package eu.nordtal.season.smp.rail;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;
import org.jspecify.annotations.Nullable;

/** The starts of the walks of one world that gave up on a chunk, kept until that chunk loads. */
final class ChunkRetries {

    private final Map<Long, Set<RailPos>> waiting = new HashMap<>();

    /**
     * Notes that a walk from {@code starts} gave up on {@code chunks}, and returns the starts to walk again now.
     * They are walked again now when one of those chunks loaded while the walk ran, else when it next loads.
     */
    Set<RailPos> gaveUp(final Set<Long> chunks, final Collection<RailPos> starts, final LongPredicate loaded) {
        final Set<RailPos> now = new HashSet<>();
        for (final long chunk : chunks) {
            if (loaded.test(chunk)) {
                now.addAll(starts);
            } else {
                waiting.computeIfAbsent(chunk, _ -> new HashSet<>()).addAll(starts);
            }
        }
        return now;
    }

    /** Returns and forgets the starts that waited for {@code chunk}, which loaded. */
    Set<RailPos> loaded(final long chunk) {
        final @Nullable Set<RailPos> starts = waiting.remove(chunk);
        return starts == null ? Set.of() : starts;
    }
}
