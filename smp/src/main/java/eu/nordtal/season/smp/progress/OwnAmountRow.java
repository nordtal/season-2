package eu.nordtal.season.smp.progress;

/**
 * What one player has put into one objective, beside its target, with zero for one never touched.
 *
 * @param key the objective's key inside its milestone
 * @param mine this player's {@code smp_contribution.amount}, or zero
 * @param target the objective's target
 */
public record OwnAmountRow(String key, long mine, long target) {}
