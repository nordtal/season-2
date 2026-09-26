package eu.nordtal.s2.proxy.update;

import eu.nordtal.s2.common.update.UpdateStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * When to speak during a countdown, and what to say - with no proxy, no database and no clock in it.
 *
 * A countdown is planned once, the moment the row is seen, as a list of {@link Beat}s each
 * carrying the delay from now to the instant its number is true. The caller schedules them
 * and cancels them if the countdown is withdrawn. Nothing here knows how that is done.
 *
 * Three rules: not a message every five seconds - chat gets two lines, at thirty seconds and at ten,
 * plus the one at zero, and the last ten seconds are a subtitle instead, a different channel that
 * does not scroll anything away. The number spoken is what is actually left, exactly, rather than to
 * the nearest poll: each beat is scheduled on the instant its own number becomes true. And a
 * countdown joined late does not replay: a proxy that comes up with seven seconds gone gets the
 * beats that are still ahead of it and no others.
 *
 * "Ahead of us" is measured on the clock the player reads, not on raw milliseconds left. Enforcing
 * it with {@code millisLeft >= threshold * 1000} would silently eat the first line of every
 * countdown: steward-worker writes {@code now() + 30s} on the database's clock and notifies in the
 * same statement, so the proxy reads the row some milliseconds later and what it holds is never
 * exactly the full duration. The fix is not a tolerance and not a longer countdown: a countdown is
 * spoken in whole seconds, so the question a beat asks is whether a counter showing whole seconds
 * still reads its number - {@code ceil(millisLeft / 1000)} - and not whether that many milliseconds
 * are left to the last one. A beat whose exact instant has just gone past is said now; a beat whose
 * number the clock no longer shows is not said at all.
 *
 * A vanished row is not a cancellation: the row does not disappear, it changes status, so
 * {@link #gone(UpdateStatus)} takes the status and says what actually happened. Nothing here guesses
 * from timing.
 */
public final class Countdown {

    /**
     * Chat lines, in seconds remaining. Three, and then zero, which is {@link #beats}' own.
     *
     * Each of these is a chat line and a title, so a second named here is deliberately absent from
     * the tick sequence below - see {@link #beats}.
     *
     * These are matched against
     * {@link eu.nordtal.s2.common.update.UpdateDirectory#UPDATE_COUNTDOWN}: a threshold longer than
     * the countdown is a line planned for an instant that has already passed, and rule three drops
     * it in silence. {@code CountdownTest} holds them against each other so that raising one alone
     * fails rather than goes quiet.
     */
    static final List<Long> CHAT_THRESHOLDS = List.of(60L, 30L, 10L);

    /** The last stretch, one subtitle per second. */
    static final long SUBTITLES_FROM = 10L;

    /**
     * One thing to say, and how long from now to wait before saying it.
     *
     * @param delay from the instant {@link #beats} was called
     */
    public record Beat(Duration delay, Announcement announcement) {}

    /** The request being counted down, so a second one starts a fresh set. */
    private @Nullable Long watching;

    /** Whether the zero beat has already been produced, so it is never said twice. */
    private boolean reachedZero;

    /**
     * Plans the whole countdown for one row.
     *
     * @param requestId which request; a different id replaces the plan
     * @param untilDue  what is left, to the millisecond
     * @return the beats still ahead, earliest first - empty when this row is already being counted
     *         down, because the beats for it are already scheduled
     */
    public Optional<List<Beat>> beats(final long requestId, final Duration untilDue) {
        if (watching != null && watching == requestId) {
            return Optional.empty();
        }
        watching = requestId;
        reachedZero = false;

        final long millisLeft = Math.max(0L, untilDue.toMillis());
        // What a counter showing whole seconds reads right now, not millisLeft, decides whether a number is ahead.
        final long secondsShown = (millisLeft + 999L) / 1000L;
        final List<Beat> beats = new ArrayList<>();

        for (final long threshold : CHAT_THRESHOLDS) {
            if (secondsShown >= threshold) {
                beats.add(atOrNow(millisLeft, threshold, new Announcement(Announcement.Kind.COUNTDOWN, threshold)));
            }
        }
        for (long second = SUBTITLES_FROM; second >= 1L; second--) {
            // A second that already has a chat line gets no tick: the chat line draws its own second's title.
            if (secondsShown >= second && !CHAT_THRESHOLDS.contains(second)) {
                beats.add(atOrNow(millisLeft, second, new Announcement(Announcement.Kind.TICK, second)));
            }
        }
        beats.add(new Beat(Duration.ofMillis(millisLeft), new Announcement(Announcement.Kind.NOW, 0L)));

        // Sorted rather than emitted in order, so a reader of a test can see one timeline.
        beats.sort(java.util.Comparator.comparing(Beat::delay));
        return Optional.of(beats);
    }

    /**
     * One beat, on the instant its number becomes true - or now, when that instant has just passed.
     *
     * Never a negative delay, which is the only thing that could come out of the rounding above:
     * a number the clock still shows but whose exact instant is already some milliseconds behind
     * us.
     */
    private static Beat atOrNow(final long millisLeft, final long seconds, final Announcement announcement) {
        return new Beat(Duration.ofMillis(Math.max(0L, millisLeft - seconds * 1000L)), announcement);
    }

    /**
     * @return the request this countdown is following, or {@code null} when none. The caller needs
     *         it to look the row up once it stops counting down
     */
    public @Nullable Long watching() {
        return watching;
    }

    /** Called by the caller when it delivers the zero beat, so the poll below stays quiet. */
    public void zeroReached() {
        reachedZero = true;
    }

    /**
     * Nothing is counting down any more, and this is what became of the row.
     *
     * @param status what the row says now, or {@code null} when it is gone from the table entirely
     * @return what to say, or empty when there is nothing worth saying - nothing was being counted
     *         down, or the countdown ran out and has already announced itself
     */
    public Optional<Announcement> gone(final @Nullable UpdateStatus status) {
        final boolean wasCounting = watching != null && !reachedZero;
        watching = null;
        reachedZero = false;

        if (!wasCounting) {
            return Optional.empty();
        }
        if (status == null) {
            // The row was deleted rather than finished, which reads as a cancellation to the player.
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
