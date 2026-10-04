package eu.nordtal.s2.hungergames.db;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One member on a team of the round, as this plugin plays them.
 *
 * {@code mcUuid} is null for a player who never linked, who therefore has no body to teleport.
 */
public record RosterEntry(
        UUID memberId,
        UUID teamId,
        String teamName,
        boolean ready,
        @Nullable UUID mcUuid) {}
