package eu.nordtal.s2.hungergames.db;

import java.util.UUID;

/** One row of {@code hg_member}: one player's membership in one team, for one game. */
public record HgMember(UUID id, UUID teamId, UUID gameId, String discordId, MemberState state, boolean ready) {
}
