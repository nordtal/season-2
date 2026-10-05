package eu.nordtal.season.common.time;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.function.LongFunction;
import org.jspecify.annotations.Nullable;

/**
 * When a countdown speaks: the whole seconds left at which it says so, and its end.
 *
 * Every countdown of the network is one: a restart, a game's start, a duel. What a beat says is the caller's.
 */
public final class CountdownPlan {

    /**
     * One thing to say, and how long to wait before saying it.
     *
     * @param delay   from the moment {@link #beats} was called
     * @param seconds what is left when it is said, zero at the end
     * @param said    what the caller gave for that number
     */
    public record Beat<T>(Duration delay, long seconds, T said) {}

    /** Descending, distinct and positive. */
    private final List<Long> marks;

    private final boolean fromTheStart;

    private CountdownPlan(final List<Long> marks, final boolean fromTheStart) {
        this.marks = List.copyOf(marks);
        this.fromTheStart = fromTheStart;
    }

    /** Returns a plan that speaks at these seconds left; a number not above zero is left out. */
    public static CountdownPlan at(final long... seconds) {
        final TreeSet<Long> marks = new TreeSet<>(Comparator.reverseOrder());
        Arrays.stream(seconds).filter(second -> second > 0).forEach(marks::add);
        return new CountdownPlan(new ArrayList<>(marks), false);
    }

    /** Returns this plan, and also every whole second from {@code from} down to one: the last stretch. */
    public CountdownPlan everySecondFrom(final long from) {
        final TreeSet<Long> all = new TreeSet<>(Comparator.reverseOrder());
        all.addAll(marks);
        for (long second = from; second >= 1L; second--) {
            all.add(second);
        }
        return new CountdownPlan(new ArrayList<>(all), fromTheStart);
    }

    /** Returns this plan, and also a beat the moment it is planned, so the first line names the whole time left. */
    public CountdownPlan fromTheStart() {
        return new CountdownPlan(marks, true);
    }

    /** Returns the seconds this plan speaks at, highest first, besides the start and the end. */
    public List<Long> marks() {
        return marks;
    }

    /**
     * Plans the beats still ahead.
     *
     * @param left what is left, to the millisecond; nothing left is only the end
     * @param at   what to say with a number of seconds left
     * @param end  what to say at zero, or {@code null} for nothing
     * @return the beats, earliest first
     */
    public <T> List<Beat<T>> beats(final Duration left, final LongFunction<? extends T> at, final @Nullable T end) {
        final long millisLeft = Math.max(0L, left.toMillis());
        // A beat is due once a whole-second counter reads its number, so a plan made late keeps only what is ahead.
        final long shown = (millisLeft + 999L) / 1000L;
        final List<Beat<T>> beats = new ArrayList<>();

        if (fromTheStart && shown > 0L && !marks.contains(shown)) {
            beats.add(new Beat<>(Duration.ZERO, shown, at.apply(shown)));
        }
        for (final long mark : marks) {
            if (shown >= mark) {
                // On the instant its number becomes true, or now if that instant has just passed.
                beats.add(new Beat<>(Duration.ofMillis(Math.max(0L, millisLeft - mark * 1000L)), mark, at.apply(mark)));
            }
        }
        if (end != null) {
            beats.add(new Beat<>(Duration.ofMillis(millisLeft), 0L, end));
        }
        beats.sort(Comparator.comparing((Beat<T> beat) -> beat.delay()));
        return List.copyOf(beats);
    }
}
