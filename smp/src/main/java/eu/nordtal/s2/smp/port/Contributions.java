package eu.nordtal.s2.smp.port;

import eu.nordtal.s2.common.id.DiscordId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Progress's side of a hand-in: crediting what was delivered, and what one player has put in so far. */
public interface Contributions {

    /**
     * Credits {@code delta} towards an objective of the active milestone, with all it finishes, in one transaction.
     * Blocking, so call it from an async task; nothing is written when it throws.
     *
     * @param discordId who to credit
     * @param objectiveKey which objective of the active milestone
     * @param delta how much, in the objective's own unit
     * @param completedBy the player the credit came from, or null for an admin; only changes the finishing sound
     * @return what was credited, or 0 when it is not positive or the active milestone has no such open objective
     */
    long credit(DiscordId discordId, String objectiveKey, long delta, @Nullable UUID completedBy);

    /** One line per objective of {@code milestoneKey}, with what this player put in; blocking. */
    List<OwnContributionRow> ownContributions(String milestoneKey, DiscordId discordId);
}
