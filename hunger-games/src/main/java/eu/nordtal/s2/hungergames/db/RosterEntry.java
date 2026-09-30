package eu.nordtal.s2.hungergames.db;

import eu.nordtal.s2.common.id.DiscordId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One roster row: an {@code hg_member} joined to its Minecraft account.
 *
 * {@code mcUuid} is null for a player who never linked, who therefore has no body to teleport.
 */
public record RosterEntry(
        UUID memberId,
        UUID teamId,
        String teamName,
        @Nullable Integer teamColourRgb,
        @Nullable String teamColourNamed,
        DiscordId discordId,
        MemberState memberState,
        boolean ready,
        @Nullable UUID mcUuid,
        @Nullable String mcName) {}
