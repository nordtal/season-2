package eu.nordtal.s2.hungergames.game;

import java.util.UUID;

/**
 * One effective participant at countdown time: a resolved {@code hg_member} row plus whether their
 * team was just demoted from duo to solo (docs/hunger-games.md#teams-colours-and-hearts - "a duo
 * whose partner never logged in by countdown becomes solo with full hearts").
 * <p>
 * This is the unit {@link Demotion} and the border step both count - "effective (post-demotion)
 * participants" in docs/hunger-games.md#the-border.
 * </p>
 */
public record Participant(UUID memberId, UUID teamId, String teamName, String discordId, UUID mcUuid,
                           boolean present, boolean demotedToSolo) {
}
