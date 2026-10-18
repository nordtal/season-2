package eu.nordtal.season.discordbot.access.discord;

import eu.nordtal.season.common.id.DiscordId;
import java.time.Instant;

public record AccessDeadline(DiscordId discordId, Instant validUntil) {}
