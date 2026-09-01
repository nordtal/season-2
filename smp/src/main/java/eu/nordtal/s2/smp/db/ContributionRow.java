package eu.nordtal.s2.smp.db;

/**
 * How much one person put into one objective.
 *
 * <p>The unit is the objective's own: items handed in, blocks mined, or 1 for an advancement. What
 * makes it comparable across objectives is that the share is always measured against that
 * objective's target, never against another objective's.
 */
public record ContributionRow(String discordId, long amount) {
}
