package eu.nordtal.season.smp.rail;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;
import org.jspecify.annotations.Nullable;

/** The rails of one world that a re-check could not read as their chunk failed to load, kept until it loads. */
final class ChunkRetries {

    private final Map<Long, Set<RailPos>> waiting = new HashMap<>();

    /**
     * Notes the rails a re-check gave up on, by chunk key, and returns those to check again now.
     * The rails of a chunk that loaded while it ran are checked again now, the others once their chunk next loads.
     */
    Set<RailPos> gaveUp(final Map<Long, Set<RailPos>> rails, final LongPredicate loaded) {
        final Set<RailPos> now = new HashSet<>();
        rails.forEach((chunk, positions) -> {
            if (loaded.test(chunk)) {
                now.addAll(positions);
            } else {
                waiting.computeIfAbsent(chunk, _ -> new HashSet<>()).addAll(positions);
            }
        });
        return now;
    }

    /** Returns and forgets the rails that waited for {@code chunk}, which loaded. */
    Set<RailPos> loaded(final long chunk) {
        final @Nullable Set<RailPos> rails = waiting.remove(chunk);
        return rails == null ? Set.of() : rails;
    }
}
