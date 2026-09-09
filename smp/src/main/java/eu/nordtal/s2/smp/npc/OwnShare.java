package eu.nordtal.s2.smp.npc;

import eu.nordtal.s2.smp.db.OwnContributionRow;
import eu.nordtal.s2.smp.wheel.PrizeDraw;

import java.util.List;

/**
 * What a player has put into the active milestone, as the one line the NPC menu shows them.
 *
 * <h2>Why this is its own class</h2>
 * {@code docs/smp.md} names "the player's own contribution" as content of this menu, and until
 * 2026-09-09 nothing in the repository showed it. The arithmetic below is the whole of what the
 * line says, so it lives apart from the menu and is asserted directly - a share drawn from a
 * database read is otherwise only checkable by contributing to an objective on a running server.
 *
 * <h2>The two numbers, and why each is the one it is</h2>
 * <b>The percentage is per milestone, summed.</b> Everything else in this design is per objective -
 * the aura split, the qualifying threshold, the spins - but a single figure on one line has to be
 * about the whole thing a player is looking at, so it is what they contributed over what the
 * milestone asked for. That makes it a different number from any one card's share, which is why the
 * tooltip carries the per-objective figures beside it.
 *
 * <p><b>The spin count is per objective, summed</b>, because that is how spins are actually granted:
 * {@code ObjectiveEngine} runs {@link PrizeDraw#extraSpinsFor} against each objective's own share
 * when that objective completes. Summing the objectives is therefore the true projection and an
 * aggregate percentage put through the thresholds once would not be - a player at 30 % of one
 * objective and nothing of three others earns three spins, not one.</p>
 *
 * <p><b>It is a projection and not a balance.</b> None of these spins has been granted; they arrive
 * when the objective completes, and an objective that never completes grants none. The menu's own
 * wording and its tooltip both have to say so, which is a thing only a bundle can do.</p>
 */
public final class OwnShare {

    private OwnShare() {
    }

    /**
     * One objective's line: what this player put in, against what was asked.
     *
     * @param key     the objective's key, for looking its name up in the bundle
     * @param percent this player's share of that objective's target, 0 to 100 and not clamped above
     *                - over-collection is real, and a player who delivered twice the target should
     *                see that rather than a tidy 100
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
     * <p>A target of zero answers zero rather than dividing: the schema's CHECK makes a positive
     * target the only legal one, so this is the case that cannot happen and would be a division by
     * zero on the one screen every player opens if it ever did.</p>
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
     * <p>One decimal because the qualifying threshold is 2 % and the first band above it is 10 %:
     * a whole number would round a 2.4 % share to "2" and a 1.6 % one to "2" as well, and those two
     * players are on opposite sides of whether they are paid at all. The locale is what puts a comma
     * in German and a point in English, which is the whole reason this is not {@code String.valueOf}.
     */
    public static String format(final double percent, final java.util.Locale locale) {
        return String.format(locale, "%.1f", percent);
    }
}
