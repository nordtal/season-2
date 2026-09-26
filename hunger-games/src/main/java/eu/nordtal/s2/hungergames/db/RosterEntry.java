package eu.nordtal.s2.hungergames.db;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of the game's roster.
 *
 * An {@code hg_member} joined through {@code account_link} to the Minecraft account it belongs to.
 * {@code mcUuid} is {@code null} for a registered player who has never linked or never logged in -
 * the roster still lists them, but they have no body to teleport.
 */
public record RosterEntry(
        UUID memberId,
        UUID teamId,
        String teamName,
        @Nullable Integer teamColourRgb,
        @Nullable String teamColourNamed,
        String discordId,
        MemberState memberState,
        boolean ready,
        @Nullable UUID mcUuid) {}
