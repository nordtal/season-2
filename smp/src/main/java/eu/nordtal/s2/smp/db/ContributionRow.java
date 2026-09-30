package eu.nordtal.s2.smp.db;

import eu.nordtal.s2.common.id.DiscordId;

/** How much one person put into one objective, in that objective's own unit. */
public record ContributionRow(DiscordId discordId, long amount) {}
