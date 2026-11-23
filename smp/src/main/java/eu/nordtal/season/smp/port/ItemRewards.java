package eu.nordtal.season.smp.port;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.smp.milestone.Objective.ItemReward;
import java.util.UUID;

/** Stores and hands out the items earned when an objective completes. */
public interface ItemRewards {

    /** Stores the item in the open transaction of the caller. */
    void queue(DiscordId recipient, ItemReward reward);

    /** Starts the hand-out to an online recipient after the transaction commits. */
    void handOutIfOnline(DiscordId recipient);

    /** Starts the hand-out to {@code player}, an online player found by their Minecraft id. */
    void handOutTo(UUID player);
}
