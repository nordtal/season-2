package eu.nordtal.s2.smp.db;

/**
 * What one player has put into one objective, beside what that objective asked for.
 *
 * <p>The left join behind it is the point: an objective a player has never touched comes back at
 * {@code amount = 0} rather than not coming back at all, so the menu that reads this can say
 * "nothing yet" on that card instead of leaving it blank. A missing row and a zero row are the same
 * answer here, which is not true anywhere else in this schema.
 *
 * @param key    the objective's key inside its milestone
 * @param mine   what this player contributed - {@code smp_contribution.amount}, or zero
 * @param target what the objective asked for, which is what the aura and spin thresholds are
 *               measured against ({@code AuraPayout#QUALIFYING_PERCENT})
 */
public record OwnContributionRow(String key, long mine, long target) {
}
