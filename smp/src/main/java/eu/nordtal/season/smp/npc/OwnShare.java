package eu.nordtal.season.smp.npc;

import eu.nordtal.season.smp.port.Contributions;
import eu.nordtal.season.smp.port.OwnContributionRow;
import java.util.List;
import java.util.Optional;

/** Computes a player's share of the active milestone, which the NPC menu shows. */
public final class OwnShare {

    private OwnShare() {}

    /**
     * @param key     the objective's key, which names its bundle entry
     * @param mine    the player's own contributed amount on this objective
     * @param percent the player's share of the target in percent, not clamped to 100
     * @param spins   the extra spins that share earns if the objective completes now
     * @param budget  the spin budget of the objective
     */
    public record Line(String key, long mine, double percent, int spins, int budget) {}

    /**
     * Holds one {@link Line} per objective and the sums over the milestone.
     * {@code available} is false when the player has no linked account, telling an unavailable share from an earned 0.
     */
    public record Summary(List<Line> lines, double percent, int spins, int budget, boolean available) {

        public boolean empty() {
            return !available || (percent <= 0.0 && spins == 0);
        }

        /** Returns the player's line for {@code key}, if any. */
        public Optional<Line> line(final String key) {
            return lines.stream().filter(entry -> entry.key().equals(key)).findFirst();
        }
    }

    /** Returns an unavailable share for a player without a linked Discord account. */
    public static Summary unavailable() {
        return new Summary(List.of(), 0.0, 0, 0, false);
    }

    /** @param rows one row per objective of the active milestone, from {@link Contributions#ownContributions} */
    public static Summary of(final List<OwnContributionRow> rows) {
        final List<Line> lines = new java.util.ArrayList<>(rows.size());
        long mine = 0L;
        long target = 0L;
        int spins = 0;
        int budget = 0;
        for (final OwnContributionRow row : rows) {
            final long own = Math.max(0L, row.mine());
            final double percent = percentOf(own, row.target());
            final int earned = Math.max(0, row.spins());
            final int whole = Math.max(0, row.spinBudget());
            lines.add(new Line(row.key(), own, percent, earned, whole));
            mine += own;
            target += Math.max(0L, row.target());
            spins += earned;
            budget += whole;
        }
        return new Summary(List.copyOf(lines), percentOf(mine, target), spins, budget, true);
    }

    /** Returns {@code mine} as a percentage of {@code target}, or 0 if either is not positive. */
    public static double percentOf(final long mine, final long target) {
        if (target <= 0L || mine <= 0L) {
            return 0.0;
        }
        return (mine * 100.0) / target;
    }
}
