package eu.nordtal.season.hungergames.game;

import java.util.UUID;

/** One effective participant at countdown time: a linked member of the round and whether its team was just demoted. */
public record Participant(
        UUID memberId, UUID teamId, String teamName, UUID mcUuid, boolean present, boolean demotedToSolo) {}
