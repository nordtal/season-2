package eu.nordtal.season.hungergames.roster;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A member of a team in a round. {@code mcUuid} is {@code null} when the member has no linked account. */
public record RosterEntry(
        UUID memberId,
        UUID teamId,
        String teamName,
        boolean ready,
        @Nullable UUID mcUuid) {}
