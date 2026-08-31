package eu.nordtal.s2.hungergames.db;

import java.time.Instant;
import java.util.UUID;

/** One row of {@code hg_game}. */
public record HgGame(UUID id, GameState state, Instant started, Instant ended, UUID winnerMemberId) {
}
