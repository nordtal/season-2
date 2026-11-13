package eu.nordtal.season.steward.game;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Names milestones and objectives by their id as the game's name documents draw them, the id where no line names one.
 *
 * @param milestones draws the milestone document from its values, blank where no line holds
 * @param objectives draws the objective document from its values, blank where no line holds
 */
public record TrackNames(
        Function<Map<String, Object>, String> milestones, Function<Map<String, Object>, String> objectives) {

    private static final Logger log = LoggerFactory.getLogger(TrackNames.class);

    /** Names every milestone and objective by its id. */
    public static final TrackNames IDS = new TrackNames(values -> "", values -> "");

    public String milestone(final String key) {
        return orKey(milestones.apply(Map.of("milestone", key)), key);
    }

    public String objective(final String milestone, final String key) {
        return orKey(objectives.apply(Map.of("milestone", milestone, "objective", key)), key);
    }

    private static String orKey(final String name, final String key) {
        return name.isBlank() ? key : name;
    }

    /**
     * Returns the names {@code read} answers, read again once {@code fresh} has passed.
     * The track's readings stay cheap that way. A failed read names by id until the next one.
     */
    public static Supplier<TrackNames> cached(
            final Clock clock, final Duration fresh, final Supplier<TrackNames> read) {
        return new Supplier<>() {
            private @Nullable TrackNames last;
            private Instant readAt = Instant.MIN;

            @Override
            public synchronized TrackNames get() {
                final Instant now = clock.instant();
                if (last == null || !now.isBefore(readAt.plus(fresh))) {
                    last = readOrIds(read);
                    readAt = now;
                }
                return last;
            }
        };
    }

    private static TrackNames readOrIds(final Supplier<TrackNames> read) {
        try {
            return read.get();
        } catch (final RuntimeException unread) {
            log.warn("milestone names unreadable, ids shown: cause={}", unread.toString());
            return IDS;
        }
    }
}
