package eu.nordtal.s2.hungergames.db;

import eu.nordtal.s2.common.id.DiscordId;
import java.util.UUID;

/** One row of {@code hg_member}: one player's membership in one team, for one game. */
public record HgMember(UUID id, UUID teamId, UUID gameId, DiscordId discordId, MemberState state, boolean ready) {}
