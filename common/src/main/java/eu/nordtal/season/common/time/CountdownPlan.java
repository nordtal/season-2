package eu.nordtal.season.common.time;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.function.LongFunction;
import org.jspecify.annotations.Nullable;

/** The seconds left at which a countdown speaks, and its end. The caller decides what a beat says. */
public final class CountdownPlan {

    /** One thing to say. {@code delay} counts from the call of {@link #beats}. */
    public record Beat<T>(Duration delay, long seconds, T said) {}

    private final List<Long> marks;

    private final boolean fromTheStart;

    private CountdownPlan(final List<Long> marks, final boolean fromTheStart) {
        this.marks = List.copyOf(marks);
        this.fromTheStart = fromTheStart;
    }

    /** Returns a plan that speaks at these seconds left. A number not above zero is left out. */
    public static CountdownPlan at(final long... seconds) {
        final TreeSet<Long> marks = new TreeSet<>(Comparator.reverseOrder());
        Arrays.stream(seconds).filter(second -> second > 0).forEach(marks::add);
        return new CountdownPlan(new ArrayList<>(marks), false);
    }

    /** Returns the plan with an additional beat for every second from {@code from} down to one. */
    public CountdownPlan everySecondFrom(final long from) {
        final TreeSet<Long> all = new TreeSet<>(Comparator.reverseOrder());
        all.addAll(marks);
        for (long second = from; second >= 1L; second--) {
            all.add(second);
        }
        return new CountdownPlan(new ArrayList<>(all), fromTheStart);
    }

    /** Returns the plan with an additional beat at its start. */
    public CountdownPlan fromTheStart() {
        return new CountdownPlan(marks, true);
    }

    public List<Long> marks() {
        return marks;
    }

    /** Returns the beats still ahead, earliest first. {@code end} is said at zero, or nothing if null. */
    public <T> List<Beat<T>> beats(final Duration left, final LongFunction<? extends T> at, final @Nullable T end) {
        final long millisLeft = Math.max(0L, left.toMillis());
        final long shown = (millisLeft + 999L) / 1000L;
        final List<Beat<T>> beats = new ArrayList<>();

        if (fromTheStart && shown > 0L && !marks.contains(shown)) {
            beats.add(new Beat<>(Duration.ZERO, shown, at.apply(shown)));
        }
        for (final long mark : marks) {
            if (shown >= mark) {
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
