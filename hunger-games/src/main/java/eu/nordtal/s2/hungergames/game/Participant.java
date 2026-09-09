package eu.nordtal.s2.hungergames.game;

import java.util.UUID;

/**
 * One effective participant at countdown time: a resolved {@code hg_member} row plus whether their
 * team was just demoted from duo to solo. The unit {@link Demotion} and the border step both count.
 */
public record Participant(UUID memberId, UUID teamId, String teamName, String discordId, UUID mcUuid,
                           boolean present, boolean demotedToSolo) {
}
