package eu.nordtal.season.hungergames.game;

import java.util.UUID;

/** A participant with a linked account and whether the team of the participant was demoted to a solo team. */
public record Participant(
        UUID memberId, UUID teamId, String teamName, UUID mcUuid, boolean present, boolean demotedToSolo) {}
