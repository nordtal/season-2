package eu.nordtal.season.hungergames.game;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A row of {@code hg_game}. */
public record HgGame(
        UUID id,
        UUID registrationId,
        HgGameState state,
        @Nullable Instant started,
        @Nullable Instant ended,
        @Nullable UUID winnerMemberId) {}
