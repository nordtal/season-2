package eu.nordtal.s2.hungergames.game;

import java.util.Optional;
import java.util.UUID;

/**
 * The simultaneous-death tiebreaker: "if the last two die at the same moment, the one with more
 * kills wins. Equal kills means nobody wins" - docs/hunger-games.md#winning.
 * <p>
 * Pure function over kill counts already looked up from {@code hg_event} - the counting itself is
 * {@code HungerGamesDao#killCount}, a database concern; only the comparison lives here so it can be
 * unit tested without a database.
 * </p>
 */
public final class Tiebreak {

    private Tiebreak() {
    }

    /**
     * @param firstMemberId  one of the two simultaneously-dying members
     * @param firstKills     their kill count for this game
     * @param secondMemberId the other one
     * @param secondKills    their kill count for this game
     * @return the winner's member id, or empty when the kill counts are equal (nobody wins)
     */
    public static Optional<UUID> resolve(final UUID firstMemberId, final int firstKills,
                                          final UUID secondMemberId, final int secondKills) {
        if (firstKills == secondKills) {
            return Optional.empty();
        }
        return Optional.of(firstKills > secondKills ? firstMemberId : secondMemberId);
    }
}
