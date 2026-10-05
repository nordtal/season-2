package eu.nordtal.season.smp.port;

/**
 * What one player has put into one objective, beside its target, with zero for one never touched.
 *
 * @param key the objective's key inside its milestone
 * @param mine this player's {@code smp_contribution.amount}, or zero
 * @param target what the aura and spin thresholds are measured against
 */
public record OwnContributionRow(String key, long mine, long target) {}
