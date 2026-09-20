package eu.nordtal.s2.proxy.update;

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
 * <h2 id="onTheClock">Ahead of us is measured on the clock the player reads - season-2-ops/118</h2>
 * Rule three used to be enforced with {@code millisLeft >= threshold * 1000}, and that silently ate
 * the first line of every countdown this project ever ran. steward-worker writes {@code now() + 30s}
 * on the database's clock and notifies in the same statement; the proxy reads the row some
 * milliseconds later, so what it holds is 29 980 ms and never 30 000. Measured on the dev host on
 * 2026-09-19: {@code 12 beat(s) over PT29.979941209S} - twelve, not thirteen, and the first thing a
 * player saw was the ten-second line.
 *
 * <p>The fix is not a tolerance and not a longer countdown. A countdown is spoken in whole seconds,
 * so the question a beat asks is whether a counter showing whole seconds still reads its number -
 * {@code ceil(millisLeft / 1000)} - and not whether that many milliseconds are left to the last
 * one. A beat whose exact instant has just gone past is said <em>now</em>; a beat whose number the
 * clock no longer shows is not said at all, which is rule three, unchanged.</p>
 *
 * <h2>A vanished row is not a cancellation - finding 39, 2026-09-03</h2>
 * It was read as one until the first deployment, and that made the countdown lie about every restart
 * it ever counted down. The row does not disappear, it changes status, so {@link #gone(UpdateStatus)}
 * takes the status and says what actually happened. Nothing here guesses from timing.
 */
public final class Countdown {

    /**
     * Chat lines, in seconds remaining. Three, and then zero, which is {@link #beats}' own.
     *
     * <p>Since season-2-ops/132 each of these is a chat line <em>and</em> a title, so a second
     * named here is deliberately absent from the tick sequence below - see {@link #beats}.</p>
     *
     * <p><b>Sixty joined them on 2026-09-20</b> (season-2-ops/132), together with
     * {@link eu.nordtal.s2.common.update.UpdateDirectory#UPDATE_COUNTDOWN} going from thirty
     * seconds to sixty. The two are one decision: a threshold longer than the countdown is a line
     * planned for an instant that has already passed, and rule three drops it in silence.
     * {@code CountdownTest} holds them against each other so that raising one alone fails rather
     * than goes quiet.</p>
     */
    static final List<Long> CHAT_THRESHOLDS = List.of(60L, 30L, 10L);

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
        // What a counter showing whole seconds reads right now. THIS, and not millisLeft, is what
        // decides whether a number is still ahead of us - see #onTheClock.
        final long secondsShown = (millisLeft + 999L) / 1000L;
        final List<Beat> beats = new ArrayList<>();

        for (final long threshold : CHAT_THRESHOLDS) {
            if (secondsShown >= threshold) {
                beats.add(atOrNow(millisLeft, threshold,
                        new Announcement(Announcement.Kind.COUNTDOWN, threshold)));
            }
        }
        for (long second = SUBTITLES_FROM; second >= 1L; second--) {
            // A second that already has a chat line gets no tick: since season-2-ops/132 the chat
            // line draws the title of its own second, and two titles on one second do not queue -
            // the later one replaces the earlier mid-fade. Ten is the one that collides today.
            if (secondsShown >= second && !CHAT_THRESHOLDS.contains(second)) {
                beats.add(atOrNow(millisLeft, second,
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
     * One beat, on the instant its number becomes true - or now, when that instant has just passed.
     *
     * <p>Never a negative delay, which is the only thing that could come out of the rounding above:
     * a number the clock still shows but whose exact instant is already some milliseconds behind
     * us.</p>
     */
    private static Beat atOrNow(final long millisLeft, final long seconds,
                                final Announcement announcement) {
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
