package eu.nordtal.s2.smp.npc;

import eu.nordtal.s2.smp.port.Contributions;
import eu.nordtal.s2.smp.port.OwnContributionRow;
import eu.nordtal.s2.smp.port.PrizeSource;
import java.util.List;
import java.util.function.DoubleToIntFunction;

/**
 * What a player has put into the active milestone, as the one line the NPC menu shows them.
 *
 * The percentage is per milestone, the spin count per objective, as {@link PrizeSource#extraSpinsFor} grants it.
 */
public final class OwnShare {

    private OwnShare() {}

    /**
     * One objective's line: what this player put in, against what was asked.
     *
     * @param key     the objective's key, for looking its name up in the bundle
     * @param percent this player's share of the target, not clamped, because over-collection is real
     * @param spins   how many extra spins that share is on track for, when it completes
     */
    public record Line(String key, double percent, int spins) {}

    /** The whole answer: one line per objective, and the two summary numbers. */
    public record Summary(List<Line> lines, double percent, int spins) {

        public boolean empty() {
            return percent <= 0.0 && spins == 0;
        }
    }

    /**
     * Summarises a player's contributions.
     *
     * @param rows     one per objective of the active milestone, from {@link Contributions#ownContributions}
     * @param spinsFor the spins a share earns, {@link PrizeSource#extraSpinsFor}, which the payout also asks
     */
    public static Summary of(final List<OwnContributionRow> rows, final DoubleToIntFunction spinsFor) {
        final List<Line> lines = new java.util.ArrayList<>(rows.size());
        long mine = 0L;
        long target = 0L;
        int spins = 0;
        for (final OwnContributionRow row : rows) {
            final double percent = percentOf(row.mine(), row.target());
            final int earned = spinsFor.applyAsInt(percent);
            lines.add(new Line(row.key(), percent, earned));
            mine += Math.max(0L, row.mine());
            target += Math.max(0L, row.target());
            spins += earned;
        }
        return new Summary(List.copyOf(lines), percentOf(mine, target), spins);
    }

    /** A share as a percentage; a target of zero answers zero. */
    public static double percentOf(final long mine, final long target) {
        if (target <= 0L || mine <= 0L) {
            return 0.0;
        }
        return (mine * 100.0) / target;
    }
}
