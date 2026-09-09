package eu.nordtal.s2.smp.npc;

import eu.nordtal.s2.smp.db.OwnContributionRow;
import eu.nordtal.s2.smp.wheel.PrizeDraw;

import java.util.List;

/**
 * What a player has put into the active milestone, as the one line the NPC menu shows them.
 *
 * <p>Kept apart from the menu so the arithmetic can be asserted without a running server.
 *
 * <p>The two summary numbers are computed differently on purpose. <b>The percentage is per
 * milestone, summed</b>: one figure has to be about the whole thing the player is looking at, so it
 * differs from any single card's share. <b>The spin count is per objective, summed</b>, because
 * that is how spins are granted - {@link PrizeDraw#extraSpinsFor} runs against each objective's own
 * share as it completes, so a player at 30 % of one objective and nothing of three others earns
 * three spins, not one.
 *
 * <p>It is a projection, not a balance: none of these spins has been granted, and an objective that
 * never completes grants none.
 */
public final class OwnShare {

    private OwnShare() {
    }

    /**
     * One objective's line: what this player put in, against what was asked.
     *
     * @param key     the objective's key, for looking its name up in the bundle
     * @param percent this player's share of that objective's target, not clamped above 100 because
     *                over-collection is real and should be visible
     * @param spins   how many extra spins that share is on track for, when it completes
     */
    public record Line(String key, double percent, int spins) {
    }

    /** The whole answer: one line per objective, and the two summary numbers. */
    public record Summary(List<Line> lines, double percent, int spins) {

        /** Whether this player has contributed nothing at all to this milestone. */
        public boolean empty() {
            return percent <= 0.0 && spins == 0;
        }
    }

    /**
     * @param rows      one per objective of the active milestone, from {@code ownContributions}
     * @param thresholds {@code config.yml#wheel-extra-spin-percents}, the same list
     *                   {@code ObjectiveEngine} pays out against
     */
    public static Summary of(final List<OwnContributionRow> rows, final List<Integer> thresholds) {
        final List<Line> lines = new java.util.ArrayList<>(rows.size());
        long mine = 0L;
        long target = 0L;
        int spins = 0;
        for (final OwnContributionRow row : rows) {
            final double percent = percentOf(row.mine(), row.target());
            final int earned = PrizeDraw.extraSpinsFor(thresholds, percent);
            lines.add(new Line(row.key(), percent, earned));
            mine += Math.max(0L, row.mine());
            target += Math.max(0L, row.target());
            spins += earned;
        }
        return new Summary(List.copyOf(lines), percentOf(mine, target), spins);
    }

    /**
     * A share as a percentage.
     *
     * <p>A target of zero answers zero rather than dividing.</p>
     */
    public static double percentOf(final long mine, final long target) {
        if (target <= 0L || mine <= 0L) {
            return 0.0;
        }
        return (mine * 100.0) / target;
    }

    /**
     * The percentage as a player reads it: one decimal, in their own language.
     *
     * <p>One decimal because the qualifying threshold is 2 %: whole numbers would round a 1.6 % and
     * a 2.4 % share to the same "2", either side of being paid at all. The locale decides between a
     * comma and a point.
     */
    public static String format(final double percent, final java.util.Locale locale) {
        return String.format(locale, "%.1f", percent);
    }
}
