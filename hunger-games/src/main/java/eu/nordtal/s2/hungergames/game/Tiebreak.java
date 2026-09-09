package eu.nordtal.s2.hungergames.game;

import java.util.Optional;
import java.util.UUID;

/**
 * The simultaneous-death tiebreaker: if the last two die at the same moment, the one with more
 * kills wins, and equal kills means nobody wins. Only the comparison lives here, so it can be unit
 * tested without a database.
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
