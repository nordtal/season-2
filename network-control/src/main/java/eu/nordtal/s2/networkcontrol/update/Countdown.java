package eu.nordtal.s2.networkcontrol.update;

import eu.nordtal.s2.common.update.UpdateStatus;

import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * When to speak during a countdown, and what to say - with no proxy, no database and no clock in it.
 *
 * <h2>It builds a schedule now, and does not decide per poll - 2026-09-08</h2>
 * It used to be asked "here is what is left, is there anything to say?" on a five-second poll, and
 * the number it spoke was whatever that poll happened to observe. That was honest and it was coarse:
 * the counter said 27 where 30 was asked for, and the last ten seconds - the ones a player actually
 * reacts to - could only be spoken twice.
 *
 * <p>So a countdown is now planned once, the moment the row is seen, as a list of {@link Beat}s each
 * carrying the delay from now to the instant its number is <em>true</em>. The caller schedules them
 * and cancels them if the countdown is withdrawn. Nothing here knows how that is done.</p>
 *
 * <h2>The three rules the old class had, and what became of them</h2>
 * <ol>
 *   <li><b>Not a message every five seconds.</b> Chat gets two lines - at thirty seconds and at ten
 *       - plus the one at zero. Twelve chat lines in a minute is how a warning becomes something
 *       people learn to ignore. The last ten seconds are a <em>subtitle</em> instead, which is a
 *       different channel and does not scroll anything away.</li>
 *   <li><b>The number spoken is what is actually left.</b> Now exactly, rather than to the nearest
 *       poll: each beat is scheduled on the instant its own number becomes true.</li>
 *   <li><b>A countdown joined late does not replay.</b> A proxy that comes up with seven seconds
 *       gone gets the beats that are still ahead of it and no others - no "30 seconds" line
 *       announced twenty-three seconds after it stopped being true.</li>
 * </ol>
 *
 * <h2>A vanished row is not a cancellation - finding 39, 2026-09-03</h2>
 * It was read as one until the first deployment, and that made the countdown lie about every restart
 * it ever counted down. The row does not disappear, it changes status, so {@link #gone(UpdateStatus)}
 * takes the status and says what actually happened. Nothing here guesses from timing.
 */
public final class Countdown {

    /** Chat lines, in seconds remaining. Two, and then zero, which is {@link #beats}' own. */
    static final List<Long> CHAT_THRESHOLDS = List.of(30L, 10L);

    /** The last stretch, one subtitle per second. */
    static final long SUBTITLES_FROM = 10L;

    /**
     * One thing to say, and how long from now to wait before saying it.
     *
     * @param delay from the instant {@link #beats} was called
     */
    public record Beat(Duration delay, Announcement announcement) {
    }

    /** The request being counted down, so a second one starts a fresh set. */
    private Long watching;

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
        final List<Beat> beats = new ArrayList<>();

        for (final long threshold : CHAT_THRESHOLDS) {
            // Strictly greater: a countdown seen with exactly ten seconds left gets the ten-second
            // line now rather than a beat scheduled zero milliseconds away, and one seen with nine
            // gets no ten-second line at all, which is rule three.
            if (millisLeft >= threshold * 1000L) {
                beats.add(new Beat(Duration.ofMillis(millisLeft - threshold * 1000L),
                        new Announcement(Announcement.Kind.COUNTDOWN, threshold)));
            }
        }
        for (long second = SUBTITLES_FROM; second >= 1L; second--) {
            if (millisLeft >= second * 1000L) {
                beats.add(new Beat(Duration.ofMillis(millisLeft - second * 1000L),
                        new Announcement(Announcement.Kind.TICK, second)));
            }
        }
        beats.add(new Beat(Duration.ofMillis(millisLeft),
                new Announcement(Announcement.Kind.NOW, 0L)));

        // Sorted rather than emitted in order: the chat thresholds and the subtitles interleave at
        // ten seconds, and a caller scheduling them in the order they were built would still be
        // correct - the sort is so that a reader of a test can see one timeline.
        beats.sort(java.util.Comparator.comparing(Beat::delay));
        return Optional.of(beats);
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
            // The row was deleted rather than finished. Nothing is going to happen, which is what a
            // cancellation means to the person reading it.
            return Optional.of(new Announcement(Announcement.Kind.CANCELLED, 0L));
        }
        return switch (status) {
            // Reachable when the poll lands in the few milliseconds between the countdown running
            // out and the scheduled zero beat firing. The caller's own guard makes sure only one of
            // the two is ever said.
            case RUNNING, DONE -> Optional.of(new Announcement(Announcement.Kind.NOW, 0L));
            case FAILED -> Optional.of(new Announcement(Announcement.Kind.FAILED, 0L));
            case CANCELLED -> Optional.of(new Announcement(Announcement.Kind.CANCELLED, 0L));
            // Unreachable: the caller only gets here because no countdown was found. Silent rather
            // than a guess, and the next pass asks again.
            case PENDING -> Optional.empty();
        };
    }
}
