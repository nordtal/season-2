package eu.nordtal.season.smp.aura;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.Notifies;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * The aura book: every balance and the reason for each change, which every feature that pays or takes aura writes.
 *
 * A JDBI SqlObject, never called from the main thread.
 */
@Notifies(Channel.SMP)
public interface AuraDao {

    /** Books an aura change and its audit row together, the balance and the reason for it. */
    @Transaction
    default void addAura(final DiscordId discordId, final int delta, final String reason, final @Nullable String ref) {
        bumpAura(discordId, delta);
        recordAuraEvent(discordId, delta, reason, ref);
    }

    @SqlUpdate("""
            INSERT INTO smp_player (discord_id, aura)
            VALUES (:discordId, :delta)
            ON CONFLICT (discord_id) DO UPDATE
                SET aura = smp_player.aura + excluded.aura, updated = now()
            """)
    void bumpAura(@Bind("discordId") DiscordId discordId, @Bind("delta") int delta);

    @SqlQuery("""
            WITH inserted AS (
                INSERT INTO smp_aura_event (discord_id, delta, reason, ref)
                VALUES (:discordId, :delta, :reason, :ref)
                RETURNING id
            )
            SELECT count(*) FROM (SELECT pg_notify(:channel, '') FROM inserted) AS notified
            """)
    int recordAuraEvent(
            @Bind("discordId") DiscordId discordId,
            @Bind("delta") int delta,
            @Bind("reason") String reason,
            @Bind("ref") @Nullable String ref);

    /**
     * The highest aura, most first.
     *
     * Only players with an {@code account_link} are listed, since the board shows Minecraft players.
     */
    @SqlQuery("""
            SELECT link.mc_uuid AS mcUuid,
                   player.aura  AS aura
            FROM smp_player player
                     JOIN account_link link ON link.discord_id = player.discord_id
            ORDER BY player.aura DESC, link.mc_uuid
            LIMIT :limit
            """)
    @RegisterConstructorMapper(AuraRow.class)
    List<AuraRow> topAura(@Bind("limit") int limit);

    @SqlQuery("SELECT aura FROM smp_player WHERE discord_id = :discordId")
    Optional<Integer> auraOf(@Bind("discordId") DiscordId discordId);
}
