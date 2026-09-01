package eu.nordtal.s2.hungergames.game;

import java.util.ArrayList;
import java.util.List;

/**
 * When the lobby countdown says something.
 *
 * <h2>Why this is a class and not a loop</h2>
 * Until 2026-09-01 the countdown said <em>nothing at all</em>: {@code HungerGamesManager#start}
 * teleported everyone onto a tower, froze them, and scheduled the release a minute later with no
 * message in between. {@code hg.start.countdown} was written and translated in both languages and
 * never sent. A player standing still on a pillar with no text on screen cannot tell a countdown
 * from a hung server, which is the same failure `limbo`'s re-sent title exists to prevent.
 *
 * <h2>Why marks rather than every second</h2>
 * Sixty chat lines is not information, it is noise, and the last ten seconds are the only ones
 * anybody is actually counting. The marks are the same shape as the farm-world reset schedule in
 * docs/smp.md#the-farm-world-reset - sparse far out, dense at the end - and the full duration is
 * always announced first so that a player who arrives to a frozen screen is told immediately how
 * long it will last.
 *
 * <p>Pure arithmetic over one integer, so it is tested rather than watched.</p>
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
