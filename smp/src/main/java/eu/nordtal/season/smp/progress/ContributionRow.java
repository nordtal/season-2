package eu.nordtal.season.smp.progress;

import eu.nordtal.season.common.id.DiscordId;

/** The contribution of one player to one objective, in the unit of the objective. */
public record ContributionRow(DiscordId discordId, long amount) {}
