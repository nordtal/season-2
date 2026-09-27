package eu.nordtal.s2.discordbot.access.discord;

import java.time.Instant;

/** The end of one user's current run of access, across every grant in the chain. */
public record AccessDeadline(String discordId, Instant validUntil) {}
