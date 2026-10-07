package eu.nordtal.season.smp.port;

/**
 * What one player has put into one objective, beside its target, with zero for one never touched.
 *
 * @param key the objective's key inside its milestone
 * @param mine this player's {@code smp_contribution.amount}, or zero
 * @param target what the share is measured against
 * @param spins the extra spins this player gets of the objective's spin budget if it completed now
 * @param spinBudget the objective's whole spin budget, which {@code spins} is a part of
 */
public record OwnContributionRow(String key, long mine, long target, int spins, int spinBudget) {}
