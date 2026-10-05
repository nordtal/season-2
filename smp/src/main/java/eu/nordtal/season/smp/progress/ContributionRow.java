package eu.nordtal.season.smp.progress;

import eu.nordtal.season.common.id.DiscordId;

/** How much one person put into one objective, in that objective's own unit. */
public record ContributionRow(DiscordId discordId, long amount) {}
