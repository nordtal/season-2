package eu.nordtal.s2.hungergames.game;

import java.util.UUID;

/** One effective participant at countdown time: a resolved {@code hg_member} and whether its team was just demoted. */
public record Participant(
        UUID memberId,
        UUID teamId,
        String teamName,
        String discordId,
        UUID mcUuid,
        boolean present,
        boolean demotedToSolo) {}
