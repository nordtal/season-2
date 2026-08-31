package eu.nordtal.s2.hungergames.db;

import java.util.UUID;

/**
 * One row of the game's roster: an {@code hg_member} joined through {@code account_link} to the
 * Minecraft account it belongs to. {@code mcUuid} is {@code null} for a registered player who has
 * never linked or never logged in - the roster still lists them, but they have no body to
 * teleport.
 */
public record RosterEntry(UUID memberId, UUID teamId, String teamName, Integer teamColourRgb,
                           String teamColourNamed, String discordId, MemberState memberState,
                           boolean ready, UUID mcUuid) {
}
