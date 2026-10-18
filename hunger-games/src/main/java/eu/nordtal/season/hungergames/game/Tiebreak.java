package eu.nordtal.season.hungergames.game;

import java.util.Optional;
import java.util.UUID;

/** Decides two simultaneous deaths: the member with more kills wins. */
public final class Tiebreak {

    private Tiebreak() {}

    /** Returns the member id of the winner, or empty when the kills are equal. */
    public static Optional<UUID> resolve(
            final UUID firstMemberId, final int firstKills, final UUID secondMemberId, final int secondKills) {
        if (firstKills == secondKills) {
            return Optional.empty();
        }
        return Optional.of(firstKills > secondKills ? firstMemberId : secondMemberId);
    }
}
