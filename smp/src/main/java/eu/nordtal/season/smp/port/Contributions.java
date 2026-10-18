package eu.nordtal.season.smp.port;

import eu.nordtal.season.common.id.DiscordId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Credits objectives and reads the contributions of a player. */
public interface Contributions {

    /**
     * Credits {@code delta} to an objective of the active milestone and applies the completion that follows.
     * It blocks, so call it from an async task, and it runs in one transaction.
     *
     * @param delta the amount in the unit of the objective
     * @param completedBy the player who made the credit, or null for an admin, which selects the unlock sound
     * @return the amount credited, or 0 when {@code delta} is not positive or the objective is missing or completed
     */
    long credit(DiscordId discordId, String objectiveKey, long delta, @Nullable UUID completedBy);

    /** Returns one row per objective of {@code milestoneKey} for the player. It blocks. */
    List<OwnContributionRow> ownContributions(String milestoneKey, DiscordId discordId);
}
