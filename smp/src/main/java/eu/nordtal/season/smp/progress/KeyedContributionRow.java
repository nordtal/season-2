package eu.nordtal.season.smp.progress;

import eu.nordtal.season.common.id.DiscordId;

/** The contribution of one player to the objective {@code key}, in the unit of the objective. */
public record KeyedContributionRow(String key, DiscordId discordId, long amount) {}
