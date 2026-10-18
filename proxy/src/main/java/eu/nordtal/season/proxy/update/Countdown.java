package eu.nordtal.season.proxy.update;

import eu.nordtal.season.common.time.CountdownPlan;
import eu.nordtal.season.database.update.UpdateStatus;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Follows the request that is counted down and returns the announcement when the countdown ends. {@link CountdownPlan}
 * decides the times.
 */
public final class Countdown {

    /** The remaining seconds at which the chat message is sent. */
    static final List<Long> CHAT_THRESHOLDS = List.of(60L, 30L, 10L);

    /** The remaining seconds from which a subtitle is sent every second. */
    static final long SUBTITLES_FROM = 10L;

    /** The plan of the chat messages and the subtitles. */
    private static final CountdownPlan PLAN = CountdownPlan.at(
                    CHAT_THRESHOLDS.stream().mapToLong(Long::longValue).toArray())
            .everySecondFrom(SUBTITLES_FROM);

    /** The id of the request that is counted down. */
    private @Nullable Long watching;

    private boolean reachedZero;

    /**
     * Plans the countdown of the request. A different request id replaces the plan.
     *
     * @return the beats that are still ahead, earliest first, or empty when the request is already planned
     */
    public Optional<List<CountdownPlan.Beat<Announcement>>> beats(final long requestId, final Duration untilDue) {
        if (watching != null && watching == requestId) {
            return Optional.empty();
        }
        watching = requestId;
        reachedZero = false;
        return Optional.of(PLAN.beats(
                untilDue,
                seconds -> new Announcement(
                        CHAT_THRESHOLDS.contains(seconds) ? Announcement.Kind.COUNTDOWN : Announcement.Kind.TICK,
                        seconds),
                new Announcement(Announcement.Kind.NOW, 0L)));
    }

    /** Returns the id of the request, or {@code null} without a request. */
    public @Nullable Long watching() {
        return watching;
    }

    /** Records that the zero beat was sent. {@link #gone} then returns empty. */
    public void zeroReached() {
        reachedZero = true;
    }

    /**
     * Returns the announcement for a request that is not counted down any more.
     *
     * @param status the status of the request, {@code null} when the request is deleted
     * @return the announcement, or empty when no countdown ran or the zero beat was sent
     */
    public Optional<Announcement> gone(final @Nullable UpdateStatus status) {
        final boolean wasCounting = watching != null && !reachedZero;
        watching = null;
        reachedZero = false;

        if (!wasCounting) {
            return Optional.empty();
        }
        if (status == null) {
            return Optional.of(new Announcement(Announcement.Kind.CANCELLED, 0L));
        }
        return switch (status) {
            case RUNNING, DONE -> Optional.of(new Announcement(Announcement.Kind.NOW, 0L));
            case FAILED -> Optional.of(new Announcement(Announcement.Kind.FAILED, 0L));
            case CANCELLED -> Optional.of(new Announcement(Announcement.Kind.CANCELLED, 0L));
            case PENDING -> Optional.empty();
        };
    }
}
