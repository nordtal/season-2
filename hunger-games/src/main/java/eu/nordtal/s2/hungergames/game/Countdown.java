package eu.nordtal.s2.hungergames.game;

import java.util.ArrayList;
import java.util.List;

/**
 * When the lobby countdown says something: sparse far out, dense at the end, with the full duration
 * announced first so a frozen player can tell a countdown from a hung server. Sixty chat lines
 * would be noise. Pure arithmetic over one integer, so it is tested rather than watched.
 */
public final class Countdown {

    /**
     * Seconds-remaining at which the countdown speaks, densest at the end. Only the ones that fit
     * inside the configured duration are used.
     */
    private static final int[] MARKS = {60, 30, 20, 10, 5, 4, 3, 2, 1};

    private Countdown() {
    }

    /**
     * @param totalSeconds the configured countdown length, {@code config.countdownSeconds()}
     * @return the seconds-remaining values to announce at, descending and without duplicates,
     *         always starting with {@code totalSeconds} itself. Empty for a countdown of zero or
     *         less, which is a legitimate configuration - it means "release at once"
     */
    public static List<Integer> marks(final int totalSeconds) {
        if (totalSeconds <= 0) {
            return List.of();
        }

        final List<Integer> marks = new ArrayList<>();
        marks.add(totalSeconds);
        for (final int mark : MARKS) {
            // Strictly less than the total, so a countdown of exactly 60 does not announce 60 twice.
            if (mark < totalSeconds) {
                marks.add(mark);
            }
        }
        return List.copyOf(marks);
    }
}
