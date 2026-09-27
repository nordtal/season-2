package eu.nordtal.s2.smp.db;

/** How much one person put into one objective, in that objective's own unit. */
public record ContributionRow(String discordId, long amount) {}
