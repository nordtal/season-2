package eu.nordtal.season.smp.progress;

/**
 * The contribution of one player to the objective {@code key}.
 *
 * @param mine the {@code smp_contribution.amount} of the player, zero without a row
 */
public record OwnAmountRow(String key, long mine, long target) {}
