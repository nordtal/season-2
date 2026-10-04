package eu.nordtal.s2.hungergames.db;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One row of {@code hg_game}, started from the round of registration {@code registrationId}. */
public record HgGame(
        UUID id,
        UUID registrationId,
        GameState state,
        @Nullable Instant started,
        @Nullable Instant ended,
        @Nullable UUID winnerMemberId) {}
