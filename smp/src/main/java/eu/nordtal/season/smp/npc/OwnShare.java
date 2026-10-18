package eu.nordtal.season.smp.npc;

import eu.nordtal.season.smp.port.Contributions;
import eu.nordtal.season.smp.port.OwnContributionRow;
import java.util.List;

/** Computes a player's share of the active milestone, which the NPC menu shows. */
public final class OwnShare {

    private OwnShare() {}

    /**
     * @param key     the objective's key, which names its bundle entry
     * @param percent the player's share of the target in percent, not clamped to 100
     * @param spins   the extra spins that share earns if the objective completes now
     * @param budget  the spin budget of the objective
     */
    public record Line(String key, double percent, int spins, int budget) {}

    /** Holds one {@link Line} per objective and the sums of percent, spins and budget over the milestone. */
    public record Summary(List<Line> lines, double percent, int spins, int budget) {

        public boolean empty() {
            return percent <= 0.0 && spins == 0;
        }
    }

    /** @param rows one row per objective of the active milestone, from {@link Contributions#ownContributions} */
    public static Summary of(final List<OwnContributionRow> rows) {
        final List<Line> lines = new java.util.ArrayList<>(rows.size());
        long mine = 0L;
        long target = 0L;
        int spins = 0;
        int budget = 0;
        for (final OwnContributionRow row : rows) {
            final double percent = percentOf(row.mine(), row.target());
            final int earned = Math.max(0, row.spins());
            final int whole = Math.max(0, row.spinBudget());
            lines.add(new Line(row.key(), percent, earned, whole));
            mine += Math.max(0L, row.mine());
            target += Math.max(0L, row.target());
            spins += earned;
            budget += whole;
        }
        return new Summary(List.copyOf(lines), percentOf(mine, target), spins, budget);
    }

    /** Returns {@code mine} as a percentage of {@code target}, or 0 if either is not positive. */
    public static double percentOf(final long mine, final long target) {
        if (target <= 0L || mine <= 0L) {
            return 0.0;
        }
        return (mine * 100.0) / target;
    }
}
