package eu.nordtal.season.proxy.update;

import eu.nordtal.season.common.time.CountdownPlan;
import eu.nordtal.season.database.update.UpdateStatus;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Follows the one request being counted down and says what became of it.
 *
 * When to speak is the network's {@link CountdownPlan}; there is no proxy, database or clock in it.
 */
public final class Countdown {

    /** Chat lines, in seconds remaining; {@code CountdownTest} holds them against {@code UPDATE_COUNTDOWN}. */
    static final List<Long> CHAT_THRESHOLDS = List.of(60L, 30L, 10L);

    /** The last stretch, one subtitle per second. */
    static final long SUBTITLES_FROM = 10L;

    /** Every chat line, then a subtitle each second; a second with a chat line gets no tick, its line draws a title. */
    private static final CountdownPlan PLAN = CountdownPlan.at(
                    CHAT_THRESHOLDS.stream().mapToLong(Long::longValue).toArray())
            .everySecondFrom(SUBTITLES_FROM);

    /** The request being counted down; a different one starts a fresh plan. */
    private @Nullable Long watching;

    private boolean reachedZero;

    /**
     * Plans the whole countdown for one row.
     *
     * @param requestId which request; a different id replaces the plan
     * @param untilDue what is left, to the millisecond
     * @return the beats still ahead, earliest first, or empty when this row is already scheduled
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

    /** Returns the request this countdown is following, or {@code null} when none. */
    public @Nullable Long watching() {
        return watching;
    }

    /** Marks the zero beat as delivered, so {@link #gone} stays quiet. */
    public void zeroReached() {
        reachedZero = true;
    }

    /**
     * Says what became of a row that is no longer counting down.
     *
     * @param status the row's status now, or {@code null} when the row is gone
     * @return what to say, or empty when nothing was counted down or zero was already announced
     */
    public Optional<Announcement> gone(final @Nullable UpdateStatus status) {
        final boolean wasCounting = watching != null && !reachedZero;
        watching = null;
        reachedZero = false;

        if (!wasCounting) {
            return Optional.empty();
        }
        if (status == null) {
            // A deleted row reads as a cancellation to the player.
            return Optional.of(new Announcement(Announcement.Kind.CANCELLED, 0L));
        }
        return switch (status) {
            // Reachable when a signal lands between the countdown running out and the zero beat firing.
            case RUNNING, DONE -> Optional.of(new Announcement(Announcement.Kind.NOW, 0L));
            case FAILED -> Optional.of(new Announcement(Announcement.Kind.FAILED, 0L));
            case CANCELLED -> Optional.of(new Announcement(Announcement.Kind.CANCELLED, 0L));
            // Unreachable: the caller only gets here because no countdown was found.
            case PENDING -> Optional.empty();
        };
    }
}
