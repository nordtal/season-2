package eu.nordtal.s2.smp.progress;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.smp.port.OwnContributionRow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * What contributing writes: an objective's amount, who put in how much, and the one completion.
 *
 * A JDBI SqlObject, never called from the main thread; {@link ObjectiveEngine} runs it inside its transactions.
 */
public interface ProgressDao {

    /**
     * Adds to an objective's collected amount, atomically in SQL.
     *
     * Not clamped to the target: over-collection is real, and clamping would throw away somebody's items.
     */
    @SqlQuery("""
            WITH updated AS (UPDATE smp_objective SET amount = amount + :delta WHERE id = :id RETURNING id)
            SELECT count(*) FROM (SELECT pg_notify('nordtal_smp', '') FROM updated) AS notified
            """)
    int addObjectiveProgress(@Bind("id") UUID id, @Bind("delta") long delta);

    @SqlUpdate("""
            INSERT INTO smp_contribution (objective_id, discord_id, amount)
            VALUES (:objectiveId, :discordId, :amount)
            ON CONFLICT (objective_id, discord_id) DO UPDATE
                SET amount  = smp_contribution.amount + excluded.amount,
                    updated = now()
            """)
    void addContribution(
            @Bind("objectiveId") UUID objectiveId, @Bind("discordId") DiscordId discordId, @Bind("amount") long amount);

    /**
     * Counts one player towards an unfinished gate, once, and adds 1 to its amount only for a player not counted yet.
     *
     * @return the gate's new amount, or empty when this player already counts or the gate is finished
     */
    @SqlQuery("""
            WITH counted AS (
                INSERT INTO smp_contribution (objective_id, discord_id, amount)
                SELECT obj.id, :discordId, 1
                FROM smp_objective obj
                WHERE obj.id = :objectiveId AND obj.completed IS NULL
                ON CONFLICT (objective_id, discord_id) DO NOTHING
                RETURNING objective_id
            ),
            updated AS (
                UPDATE smp_objective obj
                SET amount = obj.amount + 1
                FROM counted
                WHERE obj.id = counted.objective_id
                RETURNING obj.amount
            )
            SELECT updated.amount, pg_notify('nordtal_smp', '') AS notified FROM updated
            """)
    Optional<Long> countOnce(@Bind("objectiveId") UUID objectiveId, @Bind("discordId") DiscordId discordId);

    @SqlQuery("""
            SELECT discord_id AS discordId, amount AS amount
            FROM smp_contribution
            WHERE objective_id = :objectiveId AND amount > 0
            """)
    @RegisterConstructorMapper(ContributionRow.class)
    List<ContributionRow> contributionsOf(@Bind("objectiveId") UUID objectiveId);

    /**
     * What one player has put into each objective of one milestone.
     *
     * A left join, so an untouched objective comes back at zero instead of shortening the menu's list.
     */
    @SqlQuery("""
            SELECT obj.key               AS key,
                   coalesce(con.amount, 0) AS mine,
                   obj.target            AS target
            FROM smp_objective obj
            LEFT JOIN smp_contribution con
                   ON con.objective_id = obj.id AND con.discord_id = :discordId
            WHERE obj.milestone_key = :milestoneKey
            """)
    @RegisterConstructorMapper(OwnContributionRow.class)
    List<OwnContributionRow> ownContributions(
            @Bind("milestoneKey") String milestoneKey, @Bind("discordId") DiscordId discordId);

    /**
     * Marks an objective finished, once.
     *
     * The {@code completed IS NULL} guard lets only one of two simultaneous deliveries go on to pay anybody.
     */
    @SqlQuery("""
            WITH updated AS (
                UPDATE smp_objective SET completed = now() WHERE id = :id AND completed IS NULL RETURNING id
            )
            SELECT count(*) FROM (SELECT pg_notify('nordtal_smp', '') FROM updated) AS notified
            """)
    int completeObjective(@Bind("id") UUID id);
}
