package eu.nordtal.season.hungergames.game;

import java.util.Optional;
import java.util.UUID;

/** The simultaneous-death tiebreaker: more kills wins, equal kills means nobody wins. */
public final class Tiebreak {

    private Tiebreak() {}

    /** Returns the winner's member id, or empty when the kill counts are equal. */
    public static Optional<UUID> resolve(
            final UUID firstMemberId, final int firstKills, final UUID secondMemberId, final int secondKills) {
        if (firstKills == secondKills) {
            return Optional.empty();
        }
        return Optional.of(firstKills > secondKills ? firstMemberId : secondMemberId);
    }
}
