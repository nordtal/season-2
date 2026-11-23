package eu.nordtal.season.smp.pending;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.smp.milestone.Objective.ItemReward;
import java.util.List;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/** The items smp holds for a player until their next join. A JDBI SqlObject; never call it from the main thread. */
public interface PendingItems {

    /** @param returnedStake whether the item is a duel stake that goes back to its owner, rather than a prize */
    record Item(
            String item, int amount, String data, String structure, boolean objectiveReward, boolean returnedStake) {}

    default void store(final DiscordId discordId, final String item, final int amount, final String data) {
        store(discordId, item, amount, data, "", false, false);
    }

    default void storeReward(final DiscordId recipient, final ItemReward reward) {
        store(recipient, reward.item(), reward.amount(), reward.data(), reward.structure(), true, false);
    }

    @SqlUpdate("""
            INSERT INTO smp_pending_item (discord_id, item, amount, data, structure, objective_reward, returned_stake)
            VALUES (:discordId, :item, :amount, :data, :structure, :objectiveReward, :returnedStake)
            """)
    void store(
            @Bind("discordId") DiscordId discordId,
            @Bind("item") String item,
            @Bind("amount") int amount,
            @Bind("data") String data,
            @Bind("structure") String structure,
            @Bind("objectiveReward") boolean objectiveReward,
            @Bind("returnedStake") boolean returnedStake);

    @SqlQuery("SELECT EXISTS (SELECT FROM smp_pending_item WHERE discord_id = :discordId)")
    boolean waitsFor(@Bind("discordId") DiscordId discordId);

    @SqlQuery("""
            DELETE FROM smp_pending_item
            WHERE discord_id = :discordId
            RETURNING item, amount, data, structure, objective_reward, returned_stake
            """)
    @RegisterConstructorMapper(Item.class)
    List<Item> claim(@Bind("discordId") DiscordId discordId);
}
