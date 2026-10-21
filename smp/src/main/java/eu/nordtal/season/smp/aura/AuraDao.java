package eu.nordtal.season.smp.aura;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.Notifies;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * The aura ledger: every balance and the reason of every change. Every feature that pays or takes aura writes it.
 *
 * A JDBI SqlObject. Never call it from the main thread.
 */
@Notifies(Channel.SMP)
public interface AuraDao {

    @Transaction
    default void addAura(final DiscordId discordId, final int delta, final AuraCause cause) {
        bumpAura(discordId, delta);
        final UUID event = recordAuraEvent(discordId, delta, cause.reason().stored());
        switch (cause) {
            case AuraCause.Duel duel -> recordDuel(event, duel.type());
            case AuraCause.Death death -> {
                if (death.damageType() != null) {
                    recordDeath(event, death.damageType());
                }
            }
            case AuraCause.Advancement advancement -> recordAdvancement(event, advancement.key());
            case AuraCause.Contribution contribution -> recordContribution(event, contribution.objectiveId());
            case AuraCause.Admin admin ->
                recordAdmin(event, admin.actor().kind().name(), admin.actor().id());
        }
    }

    /** Sets the aura of a person and records the difference with its cause. Returns the difference. */
    @Transaction
    default int setAura(final DiscordId discordId, final int aura, final AuraCause cause) {
        bumpAura(discordId, 0); // creates and locks the row, so a concurrent change waits for this one
        final int delta = aura - auraOf(discordId).orElseThrow();
        if (delta != 0) {
            addAura(discordId, delta, cause);
        }
        return delta;
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
                INSERT INTO smp_aura_event (discord_id, delta, reason)
                VALUES (:discordId, :delta, :reason)
                RETURNING id
            )
            SELECT id FROM inserted, LATERAL (SELECT pg_notify(:channel, '')) AS notified
            """)
    UUID recordAuraEvent(
            @Bind("discordId") DiscordId discordId, @Bind("delta") int delta, @Bind("reason") String reason);

    @SqlUpdate("INSERT INTO smp_aura_duel (event_id, duel_type) VALUES (:event, :type)")
    void recordDuel(@Bind("event") UUID event, @Bind("type") DuelType type);

    @SqlUpdate("INSERT INTO smp_aura_death (event_id, damage_type) VALUES (:event, :damageType)")
    void recordDeath(@Bind("event") UUID event, @Bind("damageType") String damageType);

    @SqlUpdate("INSERT INTO smp_aura_advancement (event_id, advancement) VALUES (:event, :key)")
    void recordAdvancement(@Bind("event") UUID event, @Bind("key") String key);

    @SqlUpdate("INSERT INTO smp_aura_contribution (event_id, objective_id) VALUES (:event, :objective)")
    void recordContribution(@Bind("event") UUID event, @Bind("objective") UUID objectiveId);

    @SqlUpdate("INSERT INTO smp_aura_admin (event_id, actor_kind, actor_id) VALUES (:event, :kind, :id)")
    void recordAdmin(@Bind("event") UUID event, @Bind("kind") String kind, @Bind("id") @Nullable String id);

    /**
     * Returns the players with the highest aura, highest first.
     *
     * It lists only players with an {@code account_link}.
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
