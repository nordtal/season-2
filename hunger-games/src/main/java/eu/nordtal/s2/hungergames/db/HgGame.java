package eu.nordtal.s2.hungergames.db;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One row of {@code hg_game}. */
public record HgGame(
        UUID id,
        GameState state,
        @Nullable Instant started,
        @Nullable Instant ended,
        @Nullable UUID winnerMemberId) {}
