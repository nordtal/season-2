package eu.nordtal.s2.proxy.update;

import eu.nordtal.s2.common.update.UpdateStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Plans when to speak during a countdown, and what to say, with no proxy, database or clock in it.
 *
 * A beat is due once a whole-second counter reads its number; a late join gets only the beats still ahead.
 */
public final class Countdown {

    /** Chat lines, in seconds remaining; {@code CountdownTest} holds them against {@code UPDATE_COUNTDOWN}. */
    static final List<Long> CHAT_THRESHOLDS = List.of(60L, 30L, 10L);

    /** The last stretch, one subtitle per second. */
    static final long SUBTITLES_FROM = 10L;

    /**
     * One thing to say, and how long to wait before saying it.
     *
     * @param delay from the instant {@link #beats} was called
     */
    public record Beat(Duration delay, Announcement announcement) {}

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
    public Optional<List<Beat>> beats(final long requestId, final Duration untilDue) {
        if (watching != null && watching == requestId) {
            return Optional.empty();
        }
        watching = requestId;
        reachedZero = false;

        final long millisLeft = Math.max(0L, untilDue.toMillis());
        // The seconds a counter shows, not the raw milliseconds, decide whether a number is still ahead.
        final long secondsShown = (millisLeft + 999L) / 1000L;
        final List<Beat> beats = new ArrayList<>();

        for (final long threshold : CHAT_THRESHOLDS) {
            if (secondsShown >= threshold) {
                beats.add(atOrNow(millisLeft, threshold, new Announcement(Announcement.Kind.COUNTDOWN, threshold)));
            }
        }
        for (long second = SUBTITLES_FROM; second >= 1L; second--) {
            // A second with a chat line gets no tick: the chat line draws its own title.
            if (secondsShown >= second && !CHAT_THRESHOLDS.contains(second)) {
                beats.add(atOrNow(millisLeft, second, new Announcement(Announcement.Kind.TICK, second)));
            }
        }
        beats.add(new Beat(Duration.ofMillis(millisLeft), new Announcement(Announcement.Kind.NOW, 0L)));

        beats.sort(java.util.Comparator.comparing(Beat::delay));
        return Optional.of(beats);
    }

    /**
     * One beat on the instant its number becomes true, or now if that instant has just passed; never a negative delay.
     */
    private static Beat atOrNow(final long millisLeft, final long seconds, final Announcement announcement) {
        return new Beat(Duration.ofMillis(Math.max(0L, millisLeft - seconds * 1000L)), announcement);
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
            // Reachable when the poll lands between the countdown running out and the zero beat firing.
            case RUNNING, DONE -> Optional.of(new Announcement(Announcement.Kind.NOW, 0L));
            case FAILED -> Optional.of(new Announcement(Announcement.Kind.FAILED, 0L));
            case CANCELLED -> Optional.of(new Announcement(Announcement.Kind.CANCELLED, 0L));
            // Unreachable: the caller only gets here because no countdown was found.
            case PENDING -> Optional.empty();
        };
    }
}
