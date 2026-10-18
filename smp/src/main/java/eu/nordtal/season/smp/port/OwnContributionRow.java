package eu.nordtal.season.smp.port;

/**
 * The contribution of one player to the objective {@code key}.
 *
 * @param mine the {@code smp_contribution.amount} of the player, zero without a row
 * @param spins the spins the player receives if the objective completes now
 * @param spinBudget the spin budget of the objective
 */
public record OwnContributionRow(String key, long mine, long target, int spins, int spinBudget) {}
