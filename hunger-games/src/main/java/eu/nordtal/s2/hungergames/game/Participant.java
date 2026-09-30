package eu.nordtal.s2.hungergames.game;

import eu.nordtal.s2.common.id.DiscordId;
import java.util.UUID;

/** One effective participant at countdown time: a resolved {@code hg_member} and whether its team was just demoted. */
public record Participant(
        UUID memberId,
        UUID teamId,
        String teamName,
        DiscordId discordId,
        UUID mcUuid,
        boolean present,
        boolean demotedToSolo) {}
