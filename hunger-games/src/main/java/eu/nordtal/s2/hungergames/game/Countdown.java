package eu.nordtal.s2.hungergames.game;

import java.util.ArrayList;
import java.util.List;

/** When the lobby countdown speaks: sparse far out, dense at the end, the full duration first. */
public final class Countdown {

    /** Seconds remaining at which the countdown speaks; only those inside the configured duration are used. */
    private static final int[] MARKS = {60, 30, 20, 10, 5, 4, 3, 2, 1};

    private Countdown() {}

    /**
     * Returns the seconds remaining to announce at, descending and starting with {@code totalSeconds} itself.
     *
     * Empty for a countdown of zero or less, which releases at once.
     */
    public static List<Integer> marks(final int totalSeconds) {
        if (totalSeconds <= 0) {
            return List.of();
        }

        final List<Integer> marks = new ArrayList<>();
        marks.add(totalSeconds);
        for (final int mark : MARKS) {
            // Strictly less, so a countdown of exactly 60 does not announce 60 twice.
            if (mark < totalSeconds) {
                marks.add(mark);
            }
        }
        return List.copyOf(marks);
    }
}
