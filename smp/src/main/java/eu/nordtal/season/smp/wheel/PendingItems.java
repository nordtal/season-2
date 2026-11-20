package eu.nordtal.season.smp.wheel;

import eu.nordtal.season.common.id.DiscordId;
import java.util.List;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

public interface PendingItems {

    record Item(String item, int amount, String data, String structure, boolean objectiveReward) {}

    default void store(final DiscordId discordId, final String item, final int amount, final String data) {
        store(discordId, item, amount, data, "", false);
    }

    @SqlUpdate("""
            INSERT INTO smp_pending_item (discord_id, item, amount, data, structure, objective_reward)
            VALUES (:discordId, :item, :amount, :data, :structure, :objectiveReward)
            """)
    void store(
            @Bind("discordId") DiscordId discordId,
            @Bind("item") String item,
            @Bind("amount") int amount,
            @Bind("data") String data,
            @Bind("structure") String structure,
            @Bind("objectiveReward") boolean objectiveReward);

    @SqlQuery("SELECT EXISTS (SELECT FROM smp_pending_item WHERE discord_id = :discordId)")
    boolean waitsFor(@Bind("discordId") DiscordId discordId);

    @SqlQuery("""
            DELETE FROM smp_pending_item
            WHERE discord_id = :discordId
            RETURNING item, amount, data, structure, objective_reward
            """)
    @RegisterConstructorMapper(Item.class)
    List<Item> claim(@Bind("discordId") DiscordId discordId);
}
