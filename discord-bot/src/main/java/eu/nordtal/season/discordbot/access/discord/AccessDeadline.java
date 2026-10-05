package eu.nordtal.season.discordbot.access.discord;

import eu.nordtal.season.common.id.DiscordId;
import java.time.Instant;

/** The end of one user's current run of access, across every grant in the chain. */
public record AccessDeadline(DiscordId discordId, Instant validUntil) {}
