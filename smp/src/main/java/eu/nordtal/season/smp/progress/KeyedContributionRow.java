package eu.nordtal.season.smp.progress;

import eu.nordtal.season.common.id.DiscordId;

/** How much one person put into the objective {@code key} of a milestone, in that objective's own unit. */
public record KeyedContributionRow(String key, DiscordId discordId, long amount) {}
